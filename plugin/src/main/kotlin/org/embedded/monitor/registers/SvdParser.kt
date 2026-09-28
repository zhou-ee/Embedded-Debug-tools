package org.embedded.monitor.registers

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 高性能 CMSIS-SVD 规范 XML 解析器。
 * 支持：
 * 1. 外设、寄存器与位域完整层级；
 * 2. derivedFrom 外设与寄存器继承；
 * 3. dim/dimIncrement/dimIndex 数组寄存器自动展开；
 * 4. cluster 嵌套寄存器块；
 * 5. bitRange、bitOffset/bitWidth、lsb/msb 等多种位域语法；
 * 6. enumeratedValues 枚举值与符号名映射；
 * 7. 十六进制（0x.../#...）与十进制灵活数字解析。
 */
object SvdParser {

    /** dim 展开单项上限：防御畸形/恶意 SVD 的 OOM。 */
    private const val MAX_DIM_EXPAND = 4096

    /** dimIncrement 上限（字节），防御地址算术溢出。 */
    private const val MAX_DIM_INCREMENT = 0x1000_0000L

    fun parse(file: File): SvdDevice {
        return file.inputStream().use { parse(it) }
    }

    fun parse(inputStream: InputStream): SvdDevice {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            // SVD 会被自动定位器从工程目录拾取，属不可信输入：统一禁 DTD/外部实体，
            // 防 XXE / 实体炸弹（billion laughs）
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeature("http://xml.org/sax/features/namespaces", false)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
            }
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(inputStream)
        val root = doc.documentElement

        val deviceName = root.childText("name") ?: "UnknownDevice"
        val deviceDesc = root.childText("description")

        // 收集所有外设元素
        val rawPeripherals = mutableMapOf<String, RawPeripheral>()
        val pList = root.childElements("peripherals")
        val pElements = if (pList.isNotEmpty()) {
            pList.first().childElements("peripheral")
        } else {
            root.childElements("peripheral")
        }

        for (pe in pElements) {
            val pName = pe.childText("name") ?: continue
            val derivedFrom = pe.getAttribute("derivedFrom").takeIf { it.isNotBlank() }
                ?: pe.childText("derivedFrom")
            val baseAddress = parseNumber(pe.childText("baseAddress"))
            val desc = pe.childText("description")
            val group = pe.childText("groupName")
            rawPeripherals[pName] = RawPeripheral(
                name = pName,
                derivedFrom = derivedFrom,
                baseAddress = baseAddress,
                description = desc,
                groupName = group,
                element = pe,
            )
        }

        // 解析寄存器列表并处理 derivedFrom 继承
        val peripherals = mutableListOf<SvdPeripheral>()
        for (raw in rawPeripherals.values) {
            val registers = resolveRegistersForPeripheral(raw, rawPeripherals)
            peripherals.add(
                SvdPeripheral(
                    name = raw.name,
                    description = raw.description,
                    groupName = raw.groupName,
                    baseAddress = raw.baseAddress,
                    registers = registers.sortedBy { it.address },
                )
            )
        }

        return SvdDevice(
            name = deviceName,
            description = deviceDesc,
            peripherals = peripherals.sortedBy { it.name },
        )
    }

    private data class RawPeripheral(
        val name: String,
        val derivedFrom: String?,
        val baseAddress: Long,
        val description: String?,
        val groupName: String?,
        val element: Element,
    )

    private fun resolveRegistersForPeripheral(
        raw: RawPeripheral,
        all: Map<String, RawPeripheral>,
        visited: MutableSet<String> = mutableSetOf(),
    ): List<SvdRegister> {
        if (!visited.add(raw.name)) return emptyList()

        val directRegisters = parseRegistersFromElement(raw.element, raw.baseAddress, raw.name)
        if (directRegisters.isNotEmpty()) {
            return directRegisters
        }

        // 若无自身寄存器且存在 derivedFrom，则复制目标外设的寄存器并以自身 baseAddress 重定位
        val sourceName = raw.derivedFrom ?: return emptyList()
        val sourceRaw = all[sourceName] ?: return emptyList()
        val sourceRegisters = resolveRegistersForPeripheral(sourceRaw, all, visited)

        return sourceRegisters.map { reg ->
            val relOffset = reg.addressOffset
            val absAddr = raw.baseAddress + relOffset
            val newPath = "${raw.name}/${reg.name}"
            reg.copy(
                address = absAddr,
                path = newPath,
            )
        }
    }

    private fun parseRegistersFromElement(
        pElem: Element,
        baseAddress: Long,
        peripheralName: String,
    ): List<SvdRegister> {
        val out = mutableListOf<SvdRegister>()
        val regsElem = pElem.childElements("registers").firstOrNull() ?: pElem

        for (child in regsElem.childElements()) {
            when (child.tagName) {
                "register" -> {
                    out.addAll(parseRegisterElement(child, baseAddress, peripheralName))
                }
                "cluster" -> {
                    parseCluster(child, baseAddress, peripheralName, out)
                }
            }
        }
        return out
    }

    private fun parseCluster(
        clusterElem: Element,
        parentBase: Long,
        parentPath: String,
        out: MutableList<SvdRegister>,
    ) {
        val clusterOffset = parseNumber(clusterElem.childText("addressOffset"))
        val clusterName = clusterElem.childText("name") ?: "cluster"
        val clusterBase = parentBase + clusterOffset
        val clusterPath = "$parentPath/$clusterName"

        for (child in clusterElem.childElements()) {
            when (child.tagName) {
                "register" -> {
                    out.addAll(parseRegisterElement(child, clusterBase, clusterPath))
                }
                "cluster" -> {
                    parseCluster(child, clusterBase, clusterPath, out)
                }
            }
        }
    }

    private fun parseRegisterElement(
        regElem: Element,
        baseAddress: Long,
        parentPath: String,
    ): List<SvdRegister> {
        val rawName = regElem.childText("name") ?: return emptyList()
        val offset = parseNumber(regElem.childText("addressOffset"))
        val sizeBits = (regElem.childText("size")?.let { parseNumber(it).toInt() } ?: 32).coerceAtLeast(8)
        val sizeBytes = (sizeBits / 8).coerceAtLeast(1)
        val access = regElem.childText("access")
        val resetValue = regElem.childText("resetValue")?.let { parseNumber(it) }
        val desc = regElem.childText("description")

        // 位域解析
        val fields = mutableListOf<SvdField>()
        val fieldsElem = regElem.childElements("fields").firstOrNull()
        if (fieldsElem != null) {
            for (fe in fieldsElem.childElements("field")) {
                parseFieldElement(fe)?.let { fields.add(it) }
            }
        }
        val sortedFields = fields.sortedBy { it.bitOffset }

        // 检查 dim 数组（畸形 SVD 的超大 dim/dimIndex 会展开出巨量对象，必须封顶）
        val dimStr = regElem.childText("dim")
        val dimCount = (dimStr?.let { parseNumber(it).toInt() } ?: 0).coerceIn(0, MAX_DIM_EXPAND)
        if (dimCount > 1) {
            val dimInc = (regElem.childText("dimIncrement")?.let { parseNumber(it) } ?: sizeBytes.toLong())
                .coerceIn(0L, MAX_DIM_INCREMENT)
            val dimIndexStr = regElem.childText("dimIndex")
            val indices = resolveDimIndices(dimIndexStr, dimCount)

            return (0 until dimCount).map { i ->
                val idx = indices.getOrElse(i) { i.toString() }
                val expandedName = formatDimName(rawName, idx)
                val regAddr = baseAddress + offset + i * dimInc
                val regPath = "$parentPath/$expandedName"
                SvdRegister(
                    name = expandedName,
                    description = desc,
                    address = regAddr,
                    addressOffset = offset + i * dimInc,
                    size = sizeBytes,
                    access = access,
                    resetValue = resetValue,
                    fields = sortedFields,
                    path = regPath,
                )
            }
        }

        val regAddr = baseAddress + offset
        val regPath = "$parentPath/$rawName"
        return listOf(
            SvdRegister(
                name = rawName,
                description = desc,
                address = regAddr,
                addressOffset = offset,
                size = sizeBytes,
                access = access,
                resetValue = resetValue,
                fields = sortedFields,
                path = regPath,
            )
        )
    }

    private fun parseFieldElement(fe: Element): SvdField? {
        val name = fe.childText("name") ?: return null
        val desc = fe.childText("description")
        val access = fe.childText("access")

        var bitOffset = 0
        var bitWidth = 1

        val rangeStr = fe.childText("bitRange")
        if (rangeStr != null) {
            val m = Regex("""\[(\d+):(\d+)\]""").find(rangeStr.trim())
            if (m != null) {
                val msb = m.groupValues[1].toInt()
                val lsb = m.groupValues[2].toInt()
                bitOffset = lsb
                bitWidth = (msb - lsb + 1).coerceAtLeast(1)
            }
        } else if (fe.childText("bitOffset") != null) {
            bitOffset = parseNumber(fe.childText("bitOffset")).toInt()
            bitWidth = (fe.childText("bitWidth")?.let { parseNumber(it).toInt() } ?: 1).coerceAtLeast(1)
        } else if (fe.childText("lsb") != null && fe.childText("msb") != null) {
            val lsb = parseNumber(fe.childText("lsb")).toInt()
            val msb = parseNumber(fe.childText("msb")).toInt()
            bitOffset = lsb
            bitWidth = (msb - lsb + 1).coerceAtLeast(1)
        }

        // 枚举值
        val enums = mutableListOf<Pair<Long, String>>()
        for (evs in fe.childElements("enumeratedValues")) {
            for (ev in evs.childElements("enumeratedValue")) {
                val evName = ev.childText("name") ?: continue
                val evValStr = ev.childText("value") ?: continue
                val evVal = parseNumber(evValStr)
                enums.add(evVal to evName)
            }
        }

        return SvdField(
            name = name,
            description = desc,
            bitOffset = bitOffset,
            bitWidth = bitWidth,
            access = access,
            enums = enums,
        )
    }

    private fun resolveDimIndices(dimIndexStr: String?, count: Int): List<String> {
        if (dimIndexStr.isNullOrBlank()) {
            return (0 until count).map { it.toString() }
        }
        val trimmed = dimIndexStr.trim()
        if (trimmed.contains(",")) {
            return trimmed.split(",").take(MAX_DIM_EXPAND).map { it.trim() }
        }
        val rangeMatch = Regex("""(\d+)-(\d+)""").find(trimmed)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].toInt()
            val end = rangeMatch.groupValues[2].toInt()
            if (end >= start) {
                return (start..minOf(end, start + MAX_DIM_EXPAND - 1)).map { it.toString() }
            }
        }
        return (0 until count).map { it.toString() }
    }

    private fun formatDimName(template: String, index: String): String {
        return when {
            template.contains("[%s]") -> template.replace("[%s]", index)
            template.contains("[s]") -> template.replace("[s]", index)
            template.contains("%s") -> template.replace("%s", index)
            template.endsWith("[%s]") -> template.substring(0, template.length - 4) + index
            else -> template + index
        }
    }

    fun parseNumber(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        // SVD 二进制字面量："#0110" = 0b0110 = 6（此前直接删 '#' 当十进制解析，结果错误）
        val binLiteral = s.trim().startsWith("#")
        val clean = s.trim().replace("#", "").replace("_", "")
        return try {
            when {
                binLiteral -> clean.toLong(2)
                clean.startsWith("0x", ignoreCase = true) -> clean.substring(2).toLong(16)
                clean.startsWith("0b", ignoreCase = true) -> clean.substring(2).toLong(2)
                else -> clean.toLongOrNull() ?: clean.toLongOrNull(16) ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    private fun Element.childText(name: String): String? {
        val children = this.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName == name) {
                return n.textContent?.trim()
            }
        }
        return null
    }

    private fun Element.childElements(name: String? = null): List<Element> {
        val out = mutableListOf<Element>()
        val children = this.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) {
                val elem = n as Element
                if (name == null || elem.tagName == name) {
                    out.add(elem)
                }
            }
        }
        return out
    }
}
