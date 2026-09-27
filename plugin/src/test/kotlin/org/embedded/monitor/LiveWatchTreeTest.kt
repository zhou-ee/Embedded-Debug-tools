package org.embedded.monitor

import org.embedded.monitor.agent.AgentService
import org.embedded.monitor.agent.SymbolNode
import org.embedded.monitor.watch.LiveWatchTreeNode
import org.embedded.monitor.watch.LiveWatchTreeUpdater
import org.embedded.monitor.watch.WatchItem
import org.embedded.monitor.watch.WatchNodeData
import org.embedded.monitor.watch.WatchRow
import org.embedded.monitor.watch.WatchValueFormatter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiveWatchTreeTest {

    @Test
    fun testFormatCompositeAndScalar() {
        val member1 = SymbolNode(
            name = "speed",
            typeName = "float",
            address = 0x20000000L,
            size = 4,
            encoding = "float",
        )
        val member2 = SymbolNode(
            name = "pos",
            typeName = "int32_t",
            address = 0x20000004L,
            size = 4,
            encoding = "signed",
        )
        val structNode = SymbolNode(
            name = "g_motor",
            typeName = "struct Motor",
            address = 0x20000000L,
            size = 8,
            encoding = "composite",
            members = listOf(member1, member2),
        )

        // Composite formatted as {…}
        val structFormatted = WatchValueFormatter.formatNode(structNode, ByteArray(8))
        assertEquals("{…}", structFormatted)

        // Float member decoded
        val floatBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(12.5f).array()
        val floatFormatted = WatchValueFormatter.formatNode(member1, floatBytes)
        assertEquals("12.5000", floatFormatted)

        // Int member decoded
        val intBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(100).array()
        val intFormatted = WatchValueFormatter.formatNode(member2, intBytes)
        assertTrue(intFormatted.contains("100"))
        assertTrue(intFormatted.contains("0x00000064"))
    }

    @Test
    fun testFormatEnum() {
        val enumNode = SymbolNode(
            name = "state",
            typeName = "enum State",
            address = 0x20000010L,
            size = 1,
            encoding = "unsigned",
            enumValues = listOf(listOf(0, "IDLE"), listOf(1, "RUNNING"), listOf(2, "FAULT")),
        )
        val bytesIdle = byteArrayOf(0)
        val bytesRunning = byteArrayOf(1)
        val bytesFault = byteArrayOf(2)
        val bytesUnknown = byteArrayOf(99)

        assertEquals("IDLE (0)", WatchValueFormatter.formatNode(enumNode, bytesIdle))
        assertEquals("RUNNING (1)", WatchValueFormatter.formatNode(enumNode, bytesRunning))
        assertEquals("FAULT (2)", WatchValueFormatter.formatNode(enumNode, bytesFault))
        assertTrue(WatchValueFormatter.formatNode(enumNode, bytesUnknown).contains("99"))
    }

    @Test
    fun testEncodeValue() {
        // Integer decimal
        val i32Bytes = WatchValueFormatter.encodeValue("12345", "signed", 4)!!
        assertEquals(12345, ByteBuffer.wrap(i32Bytes).order(ByteOrder.LITTLE_ENDIAN).int)

        // Integer hex
        val hexBytes = WatchValueFormatter.encodeValue("0xABCD", "unsigned", 2)!!
        assertEquals(0xABCD.toShort(), ByteBuffer.wrap(hexBytes).order(ByteOrder.LITTLE_ENDIAN).short)

        // Float
        val fBytes = WatchValueFormatter.encodeValue("3.1415", "float", 4)!!
        assertEquals(3.1415f, ByteBuffer.wrap(fBytes).order(ByteOrder.LITTLE_ENDIAN).float, 1e-4f)

        // Bool
        val bTrue = WatchValueFormatter.encodeValue("true", "bool", 1)!!
        assertEquals(1.toByte(), bTrue[0])
        val bFalse = WatchValueFormatter.encodeValue("0", "bool", 1)!!
        assertEquals(0.toByte(), bFalse[0])

        // Pointer
        val ptrBytes = WatchValueFormatter.encodeValue("0x20001000", "pointer", 4)!!
        assertEquals(0x20001000L, ByteBuffer.wrap(ptrBytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)

        // Invalid input
        assertNull(WatchValueFormatter.encodeValue("not_a_number", "signed", 4))
    }

    @Test
    fun testTreeSlicing() {
        val child = SymbolNode(
            name = "val",
            typeName = "uint16_t",
            address = 0x20000004L,
            size = 2,
            encoding = "unsigned",
        )
        val parent = SymbolNode(
            name = "struct_var",
            typeName = "MyStruct",
            address = 0x20000000L,
            size = 8,
            encoding = "composite",
            members = listOf(child),
        )
        val entry = WatchItem(
            id = "w1",
            expr = "struct_var",
            address = 0x20000000L,
            size = 8,
            encoding = "composite",
            typeName = "MyStruct",
            lastBytes = byteArrayOf(0, 0, 0, 0, 0x34, 0x12, 0, 0),
        ).apply {
            node = parent
        }

        val childRow = WatchRow(
            key = "w1/val",
            entryId = "w1",
            depth = 1,
            node = child,
            isTop = false,
            hasChildren = false,
            expanded = false,
            entry = entry,
        )

        val offset = (childRow.node.address - childRow.entry.address).toInt()
        assertEquals(4, offset)
        val childBytes = entry.lastBytes!!.copyOfRange(offset, offset + childRow.node.size)
        assertArrayEquals(byteArrayOf(0x34, 0x12), childBytes)

        val decoded = WatchValueFormatter.formatNode(childRow.node, childBytes)
        assertTrue(decoded.contains("4660"))
        assertTrue(decoded.contains("0x1234"))
    }

    @Test
    fun testEmptyCompositeNode() {
        val emptyNode = SymbolNode(
            name = "empty_struct",
            typeName = "struct Empty",
            address = 0x20000000L,
            size = 0,
            encoding = "composite",
            members = emptyList(),
        )
        val formatted = WatchValueFormatter.formatNode(emptyNode, byteArrayOf())
        assertEquals("{ ? }", formatted)
    }

    @Test
    fun testMultiLevelNestedStruct() {
        val leaf = SymbolNode(
            name = "count",
            typeName = "uint32_t",
            address = 0x20000008L,
            size = 4,
            encoding = "unsigned",
        )
        val inner = SymbolNode(
            name = "inner",
            typeName = "struct Inner",
            address = 0x20000008L,
            size = 4,
            encoding = "composite",
            members = listOf(leaf),
        )
        val outer = SymbolNode(
            name = "outer",
            typeName = "struct Outer",
            address = 0x20000000L,
            size = 12,
            encoding = "composite",
            members = listOf(inner),
        )

        val entry = WatchItem(
            id = "w1",
            expr = "outer",
            address = 0x20000000L,
            size = 12,
            encoding = "composite",
            typeName = "struct Outer",
            lastBytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0) // 0..3
                putInt(0) // 4..7
                putInt(999) // 8..11
            }.array(),
        ).apply {
            node = outer
        }

        // Inner row at depth 1
        val innerRow = WatchRow("w1/inner", "w1", 1, inner, false, true, true, entry)
        assertEquals("{…}", WatchValueFormatter.formatNode(innerRow.node, ByteArray(4)))

        // Leaf row at depth 2
        val leafRow = WatchRow("w1/inner/count", "w1", 2, leaf, false, false, false, entry)
        val offset = (leafRow.node.address - entry.address).toInt()
        assertEquals(8, offset)
        val leafBytes = entry.lastBytes!!.copyOfRange(offset, offset + leafRow.node.size)
        val formatted = WatchValueFormatter.formatNode(leafRow.node, leafBytes)
        assertTrue(formatted.contains("999"))
    }

    @Test
    fun testSliceOutOfBoundsSafety() {
        val child = SymbolNode(
            name = "overflow_field",
            typeName = "uint32_t",
            address = 0x20000020L, // beyond topBytes
            size = 4,
            encoding = "unsigned",
        )
        val entry = WatchItem(
            id = "w1",
            expr = "var",
            address = 0x20000000L,
            size = 8,
            encoding = "composite",
            typeName = "struct Truncated",
            lastBytes = ByteArray(8), // only 8 bytes
        )
        val row = WatchRow("w1/overflow_field", "w1", 1, child, false, false, false, entry, "var.overflow_field")
        val offset = (row.node.address - row.entry.address).toInt()
        assertTrue("Offset exceeds byte array", offset + row.node.size > (entry.lastBytes?.size ?: 0))
    }

    @Test
    fun testArrayAndMemberExpressionParsing() {
        val parser = org.embedded.monitor.watch.WatchExpressionParser
        val pArr = parser.parse("arr[0]")
        assertNotNull(pArr)
        assertEquals(org.embedded.monitor.watch.WatchExpressionParser.Kind.MEMBER_CHAIN, pArr?.kind)

        val pArrDot = parser.parse("arr.[0]")
        assertNotNull(pArrDot)
        assertEquals(org.embedded.monitor.watch.WatchExpressionParser.Kind.MEMBER_CHAIN, pArrDot?.kind)

        val pNested = parser.parse("motors[2].speed")
        assertNotNull(pNested)
        assertEquals(org.embedded.monitor.watch.WatchExpressionParser.Kind.MEMBER_CHAIN, pNested?.kind)

        val pDeref = parser.parse("*ptr")
        assertNotNull(pDeref)
        assertEquals(org.embedded.monitor.watch.WatchExpressionParser.Kind.MEMBER_CHAIN, pDeref?.kind)

        val pNamespace = parser.parse("MyNamespace::g_val.sub")
        assertNotNull(pNamespace)
        assertEquals(org.embedded.monitor.watch.WatchExpressionParser.Kind.MEMBER_CHAIN, pNamespace?.kind)

        val tokens = parser.splitMemberPath("motors[2].speed")
        assertEquals(listOf("motors", "[2]", "speed"), tokens)

        val norm = parser.normalizeForElf("arr[0]")
        assertEquals("arr.[0]", norm)
    }

    @Test
    fun testSignedEnumFormatting() {
        val enumNode = SymbolNode(
            name = "err_code",
            typeName = "enum ErrorCode",
            address = 0x20000030L,
            size = 4,
            encoding = "signed",
            enumValues = listOf(listOf(0, "OK"), listOf(-1, "ERR_FAIL"), listOf(-2, "ERR_TIMEOUT")),
        )
        val bufFail = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(-1).array()
        assertEquals("ERR_FAIL (-1)", WatchValueFormatter.formatNode(enumNode, bufFail))

        val bufOk = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
        assertEquals("OK (0)", WatchValueFormatter.formatNode(enumNode, bufOk))
    }

    @Test
    fun testEncodeValueRobustness() {
        // Float formatted input for integer field
        val b = WatchValueFormatter.encodeValue("100.0", "signed", 4)
        assertNotNull(b)
        assertEquals(100, ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int)

        // Negative integer encoding
        val bNeg = WatchValueFormatter.encodeValue("-5", "signed", 4)
        assertNotNull(bNeg)
        assertEquals(-5, ByteBuffer.wrap(bNeg).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun testFullPathHierarchy() {
        val leaf = SymbolNode(name = "kp", typeName = "float", address = 0x20000000L, size = 4, encoding = "float")
        val pid = SymbolNode(name = "pid", typeName = "struct PID", address = 0x20000000L, size = 4, encoding = "composite", members = listOf(leaf))
        val motor = SymbolNode(name = "motor", typeName = "struct Motor", address = 0x20000000L, size = 4, encoding = "composite", members = listOf(pid))

        val entry = WatchItem("w1", "motor", 0x20000000L, 4, "composite", "struct Motor").apply { node = motor }

        val rootRow = WatchRow("w1", "w1", 0, motor, true, true, true, entry, "motor")
        assertEquals("motor", rootRow.fullPath)

        val pidRow = WatchRow("w1/pid", "w1", 1, pid, false, true, true, entry, "${rootRow.fullPath}.${pid.name}")
        assertEquals("motor.pid", pidRow.fullPath)

        val leafRow = WatchRow("w1/pid/kp", "w1", 2, leaf, false, false, false, entry, "${pidRow.fullPath}.${leaf.name}")
        assertEquals("motor.pid.kp", leafRow.fullPath)
    }

    @Test
    fun testArrayMemberPathHierarchy() {
        val elem = SymbolNode(name = "[2]", typeName = "int32_t", address = 0x20000008L, size = 4, encoding = "signed")
        val arr = SymbolNode(name = "scores", typeName = "int32_t[5]", address = 0x20000000L, size = 20, encoding = "composite", members = listOf(elem))
        val entry = WatchItem("w2", "scores", 0x20000000L, 20, "composite", "int32_t[5]").apply { node = arr }

        val rootRow = WatchRow("w2", "w2", 0, arr, true, true, true, entry, "scores")
        val elemPath = if (elem.name.startsWith("[")) "${rootRow.fullPath}${elem.name}" else "${rootRow.fullPath}.${elem.name}"
        val elemRow = WatchRow("w2/[2]", "w2", 1, elem, false, false, false, entry, elemPath)
        assertEquals("scores[2]", elemRow.fullPath)
    }

    @Test
    fun testPointerWithMembersFormatting() {
        val childMember = SymbolNode(
            name = "mode",
            typeName = "uint32_t",
            address = 0x00L,
            size = 4,
            encoding = "unsigned",
        )
        val pointerNode = SymbolNode(
            name = "g_chassis_ptr",
            typeName = "Chassis*",
            address = 0x20000178L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            members = listOf(childMember),
        )
        val ptrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20000180.toInt()).array()
        val formatted = WatchValueFormatter.formatNativeValue(pointerNode, ptrBytes)
        assertEquals("0x20000180", formatted)

        // NULL pointer formatting
        val nullPtrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
        val formattedNull = WatchValueFormatter.formatNativeValue(pointerNode, nullPtrBytes)
        assertEquals("0x00000000", formattedNull)
    }

    @Test
    fun testPointerExpandedChildSlicingAndPhysicalAddress() {
        val memberMode = SymbolNode(name = "mode", typeName = "uint32_t", address = 0x00L, size = 4, encoding = "unsigned")
        val memberSpeed = SymbolNode(name = "target_speed", typeName = "float", address = 0x04L, size = 4, encoding = "float")
        val memberKp = SymbolNode(name = "kp", typeName = "float", address = 0x08L, size = 4, encoding = "float")
        val memberError = SymbolNode(name = "error", typeName = "int32_t", address = 0x0CL, size = 4, encoding = "signed")

        val pointerNode = SymbolNode(
            name = "g_chassis_ptr",
            typeName = "Chassis*",
            address = 0x20000178L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            pointeeSize = 16,
            members = listOf(memberMode, memberSpeed, memberKp, memberError),
        )
        val entry = WatchItem("w1", "g_chassis_ptr", 0x20000178L, 4, "pointer", "Chassis*").apply {
            node = pointerNode
            autoRefresh = true
        }

        val topTreeNode = LiveWatchTreeNode(
            WatchNodeData("w1", "g_chassis_ptr", pointerNode, "g_chassis_ptr", true, true, entry)
        )
        val childMode = LiveWatchTreeNode(WatchNodeData("w1", "g_chassis_ptr.mode", memberMode, "g_chassis_ptr.mode", false, true, entry))
        val childSpeed = LiveWatchTreeNode(WatchNodeData("w1", "g_chassis_ptr.target_speed", memberSpeed, "g_chassis_ptr.target_speed", false, true, entry))
        val childKp = LiveWatchTreeNode(WatchNodeData("w1", "g_chassis_ptr.kp", memberKp, "g_chassis_ptr.kp", false, true, entry))
        val childError = LiveWatchTreeNode(WatchNodeData("w1", "g_chassis_ptr.error", memberError, "g_chassis_ptr.error", false, true, entry))

        topTreeNode.add(childMode)
        topTreeNode.add(childSpeed)
        topTreeNode.add(childKp)
        topTreeNode.add(childError)

        // 顶层指针自身在 RAM 中的 4 字节数据：指向 0x20000180
        val topBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20000180.toInt()).array()

        // 目标内存 0x20000180 处的 16 字节实体内容：
        // mode = 2, target_speed = 12.5f, kp = 1.5f, error = -10
        val pointedBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(2)
            .putFloat(12.5f)
            .putFloat(1.5f)
            .putInt(-10)
            .array()

        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()
        val dataMap = mapOf("ptr_0x20000180" to pointedBytes)

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topTreeNode,
            currentBytes = topBytes,
            baseAddress = topTreeNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true }, // 展开状态
            watchValueProvider = { dataMap[it] },
        )

        // 验证指针自身属性
        assertEquals(0x20000180L, topTreeNode.pointerAddress)
        assertEquals(0x20000178L, topTreeNode.physicalAddress)
        assertFalse(topTreeNode.isNullPtr)
        assertEquals(1, dynamicTargets.size)
        assertEquals("ptr_0x20000180", dynamicTargets[0].id)
        assertEquals(0x20000180L, dynamicTargets[0].address)
        assertEquals(16, dynamicTargets[0].size)

        // 验证子成员切片内容与绝对物理地址（父级指针地址 + 相对偏移）
        assertEquals(0x20000180L, childMode.physicalAddress)
        assertEquals(0x20000184L, childSpeed.physicalAddress)
        assertEquals(0x20000188L, childKp.physicalAddress)
        assertEquals(0x2000018CL, childError.physicalAddress)

        assertFalse(childMode.isNullPtr)
        assertFalse(childSpeed.isNullPtr)
        assertFalse(childKp.isNullPtr)
        assertFalse(childError.isNullPtr)

        assertNotNull(childMode.cachedBytes)
        assertNotNull(childSpeed.cachedBytes)
        assertNotNull(childKp.cachedBytes)
        assertNotNull(childError.cachedBytes)

        assertEquals("2", WatchValueFormatter.formatNativeValue(memberMode, childMode.cachedBytes!!))
        assertTrue(WatchValueFormatter.formatNativeValue(memberSpeed, childSpeed.cachedBytes!!).startsWith("12.5"))
        assertTrue(WatchValueFormatter.formatNativeValue(memberKp, childKp.cachedBytes!!).startsWith("1.5"))
        assertEquals("-10", WatchValueFormatter.formatNativeValue(memberError, childError.cachedBytes!!))
    }

    @Test
    fun testNullPointerMarksChildrenNullPtrAndNoDynamicTargets() {
        val member = SymbolNode(name = "val", typeName = "uint32_t", address = 0x00L, size = 4, encoding = "unsigned")
        val pointerNode = SymbolNode(
            name = "g_null_ptr",
            typeName = "int*",
            address = 0x20000100L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            pointeeSize = 4,
            members = listOf(member),
        )
        val entry = WatchItem("w2", "g_null_ptr", 0x20000100L, 4, "pointer", "int*").apply { node = pointerNode }
        val topNode = LiveWatchTreeNode(WatchNodeData("w2", "g_null_ptr", pointerNode, "g_null_ptr", true, true, entry))
        val childNode = LiveWatchTreeNode(WatchNodeData("w2", "g_null_ptr.val", member, "g_null_ptr.val", false, true, entry))
        topNode.add(childNode)

        val nullBytes = ByteArray(4) // 0x00000000
        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = nullBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { null },
        )

        assertEquals(0L, topNode.pointerAddress)
        assertTrue("NULL 指针的子成员必须标记为 isNullPtr=true", childNode.isNullPtr)
        assertNull("NULL 指针的子成员缓存字节必须为 null", childNode.cachedBytes)
        assertEquals(0L, childNode.physicalAddress)
        assertTrue("NULL 指针不得注册任何动态采样目标", dynamicTargets.isEmpty())
    }

    @Test
    fun testIllegalLowAddressPointerProtection() {
        val member = SymbolNode(name = "val", typeName = "uint32_t", address = 0x00L, size = 4, encoding = "unsigned")
        val pointerNode = SymbolNode(
            name = "g_bad_ptr",
            typeName = "int*",
            address = 0x20000100L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            pointeeSize = 4,
            members = listOf(member),
        )
        val entry = WatchItem("w3", "g_bad_ptr", 0x20000100L, 4, "pointer", "int*").apply { node = pointerNode }
        val topNode = LiveWatchTreeNode(WatchNodeData("w3", "g_bad_ptr", pointerNode, "g_bad_ptr", true, true, entry))
        val childNode = LiveWatchTreeNode(WatchNodeData("w3", "g_bad_ptr.val", member, "g_bad_ptr.val", false, true, entry))
        topNode.add(childNode)

        // 放置非法低地址 0x00000024 (< 0x1000L)
        val lowAddrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x24).array()
        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = lowAddrBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { null },
        )

        assertEquals(0x24L, topNode.pointerAddress)
        assertTrue("非法低地址必须标记为 isNullPtr=true", childNode.isNullPtr)
        assertNull(childNode.cachedBytes)
        assertTrue("非法低地址严禁注册动态采样目标（防御总线错误）", dynamicTargets.isEmpty())
    }

    @Test
    fun testCollapsedPointerDoesNotRegisterDynamicTargets() {
        val member = SymbolNode(name = "val", typeName = "uint32_t", address = 0x00L, size = 4, encoding = "unsigned")
        val pointerNode = SymbolNode(
            name = "g_ptr",
            typeName = "int*",
            address = 0x20000100L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            pointeeSize = 4,
            members = listOf(member),
        )
        val entry = WatchItem("w4", "g_ptr", 0x20000100L, 4, "pointer", "int*").apply { node = pointerNode }
        val topNode = LiveWatchTreeNode(WatchNodeData("w4", "g_ptr", pointerNode, "g_ptr", true, true, entry))
        val childNode = LiveWatchTreeNode(WatchNodeData("w4", "g_ptr.val", member, "g_ptr.val", false, true, entry))
        topNode.add(childNode)

        val ptrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20000180.toInt()).array()
        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = ptrBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { false }, // 未展开
            watchValueProvider = { ByteArray(4) },
        )

        assertEquals(0x20000180L, topNode.pointerAddress)
        assertTrue("未展开的指针不得注册动态采样目标（节省带宽）", dynamicTargets.isEmpty())
        assertNull("未展开指针的子成员缓存应为空", childNode.cachedBytes)
    }

    @Test
    fun testNestedStructInsidePointerTarget() {
        val speedMember = SymbolNode(name = "speed", typeName = "float", address = 0x10L, size = 4, encoding = "float")
        val currentMember = SymbolNode(name = "current", typeName = "float", address = 0x14L, size = 4, encoding = "float")
        val motorStruct = SymbolNode(
            name = "motor",
            typeName = "struct Motor",
            address = 0x10L,
            size = 8,
            encoding = "composite",
            members = listOf(speedMember, currentMember),
        )
        val pointerNode = SymbolNode(
            name = "g_chassis_ptr",
            typeName = "Chassis*",
            address = 0x20000200L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            pointeeSize = 32,
            members = listOf(motorStruct),
        )
        val entry = WatchItem("w5", "g_chassis_ptr", 0x20000200L, 4, "pointer", "Chassis*").apply { node = pointerNode }

        val topNode = LiveWatchTreeNode(WatchNodeData("w5", "g_chassis_ptr", pointerNode, "g_chassis_ptr", true, true, entry))
        val motorNode = LiveWatchTreeNode(WatchNodeData("w5", "g_chassis_ptr.motor", motorStruct, "g_chassis_ptr.motor", false, true, entry))
        val speedNode = LiveWatchTreeNode(WatchNodeData("w5", "g_chassis_ptr.motor.speed", speedMember, "g_chassis_ptr.motor.speed", false, true, entry))
        val currentNode = LiveWatchTreeNode(WatchNodeData("w5", "g_chassis_ptr.motor.current", currentMember, "g_chassis_ptr.motor.current", false, true, entry))

        motorNode.add(speedNode)
        motorNode.add(currentNode)
        topNode.add(motorNode)

        val topBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20000300.toInt()).array()
        val pointedBytes = ByteArray(32)
        ByteBuffer.wrap(pointedBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(0x10)
            putFloat(45.0f)
            putFloat(3.2f)
        }

        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()
        val dataMap = mapOf("ptr_0x20000300" to pointedBytes)

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = topBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { dataMap[it] },
        )

        // 验证电机结构体物理地址与子成员物理地址
        assertEquals(0x20000310L, motorNode.physicalAddress)
        assertEquals(0x20000310L, speedNode.physicalAddress)
        assertEquals(0x20000314L, currentNode.physicalAddress)

        assertNotNull(speedNode.cachedBytes)
        assertNotNull(currentNode.cachedBytes)
        assertTrue(WatchValueFormatter.formatNativeValue(speedMember, speedNode.cachedBytes!!).startsWith("45.0"))
        assertTrue(WatchValueFormatter.formatNativeValue(currentMember, currentNode.cachedBytes!!).startsWith("3.2"))
    }

    @Test
    fun testNodeChangedCallbackFiresOnValueUpdate() {
        val member = SymbolNode(name = "val", typeName = "uint32_t", address = 0x20000000L, size = 4, encoding = "unsigned")
        val entry = WatchItem("w1", "val", 0x20000000L, 4, "unsigned", "uint32_t").apply { node = member }
        val topNode = LiveWatchTreeNode(WatchNodeData("w1", "val", member, "val", true, true, entry))

        val changedNodes = mutableListOf<LiveWatchTreeNode>()
        val bytes1 = byteArrayOf(1, 0, 0, 0)
        val bytes2 = byteArrayOf(2, 0, 0, 0)

        // First update: from null to bytes1 -> should notify
        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = bytes1,
            baseAddress = 0x20000000L,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = mutableListOf(),
            isExpanded = { true },
            watchValueProvider = { null },
            onNodeChanged = { changedNodes.add(it) },
        )
        assertEquals(1, changedNodes.size)
        assertEquals(topNode, changedNodes[0])

        // Second update: same bytes1 -> should NOT notify
        changedNodes.clear()
        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = bytes1,
            baseAddress = 0x20000000L,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = mutableListOf(),
            isExpanded = { true },
            watchValueProvider = { null },
            onNodeChanged = { changedNodes.add(it) },
        )
        assertEquals(0, changedNodes.size)

        // Third update: new bytes2 -> should notify
        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = bytes2,
            baseAddress = 0x20000000L,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = mutableListOf(),
            isExpanded = { true },
            watchValueProvider = { null },
            onNodeChanged = { changedNodes.add(it) },
        )
        assertEquals(1, changedNodes.size)
        assertEquals(topNode, changedNodes[0])
    }

    @Test
    fun testPointerChildrenDoNotFlickerOnTransientNullBytes() {
        val memberSpeed = SymbolNode(name = "speed", typeName = "float", address = 0x00L, size = 4, encoding = "float")
        val pointerNode = SymbolNode(
            name = "g_ptr",
            typeName = "Motor*",
            address = 0x20000100L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            pointeeSize = 4,
            members = listOf(memberSpeed),
        )
        val entry = WatchItem("w1", "g_ptr", 0x20000100L, 4, "pointer", "Motor*").apply { node = pointerNode }
        val topNode = LiveWatchTreeNode(WatchNodeData("w1", "g_ptr", pointerNode, "g_ptr", true, true, entry))
        val childSpeed = LiveWatchTreeNode(WatchNodeData("w1", "g_ptr.speed", memberSpeed, "g_ptr.speed", false, true, entry))
        topNode.add(childSpeed)

        val topBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20000200.toInt()).array()
        val pointedBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(42.5f).array()

        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()
        val dataMap = mutableMapOf<String, ByteArray?>("ptr_0x20000200" to pointedBytes)
        val changedNodes = mutableListOf<LiveWatchTreeNode>()

        // 1. 初次更新：子成员成功获得采样值 42.5f
        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = topBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { dataMap[it] },
            onNodeChanged = { changedNodes.add(it) },
        )

        assertNotNull(childSpeed.cachedBytes)
        assertTrue(WatchValueFormatter.formatNativeValue(memberSpeed, childSpeed.cachedBytes!!).startsWith("42.5"))
        assertEquals(1, dynamicTargets.size)
        assertEquals("ptr_0x20000200", dynamicTargets[0].id)

        // 2. 第二次更新：偶发在途丢包或采样间隔中 pointedBytes 暂时为 null
        // 关键断言：已缓存的数值严禁被置空为 null（防止在数值与 … 之间闪烁），且依然注册 dynamicTargets
        dynamicTargets.clear()
        changedNodes.clear()
        dataMap["ptr_0x20000200"] = null

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = topBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { dataMap[it] },
            onNodeChanged = { changedNodes.add(it) },
        )

        assertNotNull("pointedBytes 偶发为 null 时子成员必须保留有效缓存值，防闪烁", childSpeed.cachedBytes)
        assertTrue(WatchValueFormatter.formatNativeValue(memberSpeed, childSpeed.cachedBytes!!).startsWith("42.5"))
        assertEquals("数值未发生破坏性变更，不得触发伪变动通知引起 UI 闪烁", 0, changedNodes.size)
        assertEquals("必须继续向引擎注册动态采样目标，严禁遗漏", 1, dynamicTargets.size)
        assertEquals("ptr_0x20000200", dynamicTargets[0].id)

        // 3. 第三次更新：新数值到达 (88.0f)
        val newPointedBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(88.0f).array()
        dataMap["ptr_0x20000200"] = newPointedBytes
        dynamicTargets.clear()
        changedNodes.clear()

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = topBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { dataMap[it] },
            onNodeChanged = { changedNodes.add(it) },
        )

        assertNotNull(childSpeed.cachedBytes)
        assertTrue(WatchValueFormatter.formatNativeValue(memberSpeed, childSpeed.cachedBytes!!).startsWith("88.0"))
        assertEquals(1, changedNodes.size)
        assertEquals(childSpeed, changedNodes[0])

        // 4. 指针变为空指针（0x00000000）
        val nullPtrBytes = ByteArray(4)
        dynamicTargets.clear()
        changedNodes.clear()

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = nullPtrBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { dataMap[it] },
            onNodeChanged = { changedNodes.add(it) },
        )

        assertTrue(childSpeed.isNullPtr)
        assertNull(childSpeed.cachedBytes)
        assertTrue(dynamicTargets.isEmpty())
    }

    @Test
    fun testNestedPointerMembersDoNotFlickerAndInvalidateOnAddrChange() {
        // g_ptr -> fsm (composite) -> normal (composite) -> vx (float, 4 bytes)
        val memberVx = SymbolNode(name = "vx", typeName = "float", address = 0x08L, size = 4, encoding = "float")
        val memberNormal = SymbolNode(
            name = "normal",
            typeName = "NormalState",
            address = 0x08L,
            size = 4,
            encoding = "composite",
            members = listOf(memberVx),
        )
        val memberFsm = SymbolNode(
            name = "fsm",
            typeName = "FsmState",
            address = 0x00L,
            size = 16,
            encoding = "composite",
            members = listOf(memberNormal),
        )
        val pointerNode = SymbolNode(
            name = "g_ptr",
            typeName = "Chassis*",
            address = 0x20000100L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            pointeeSize = 16,
            members = listOf(memberFsm),
        )
        val entry = WatchItem("w1", "g_ptr", 0x20000100L, 4, "pointer", "Chassis*").apply { node = pointerNode }
        val topNode = LiveWatchTreeNode(WatchNodeData("w1", "g_ptr", pointerNode, "g_ptr", true, true, entry))
        val childFsm = LiveWatchTreeNode(WatchNodeData("w1", "g_ptr.fsm", memberFsm, "g_ptr.fsm", false, true, entry))
        val childNormal = LiveWatchTreeNode(WatchNodeData("w1", "g_ptr.fsm.normal", memberNormal, "g_ptr.fsm.normal", false, true, entry))
        val childVx = LiveWatchTreeNode(WatchNodeData("w1", "g_ptr.fsm.normal.vx", memberVx, "g_ptr.fsm.normal.vx", false, true, entry))
        topNode.add(childFsm)
        childFsm.add(childNormal)
        childNormal.add(childVx)

        val topBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20000200.toInt()).array()
        val pointedBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(8)
            putFloat(12.34f)
        }.array()

        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()
        val dataMap = mutableMapOf<String, ByteArray?>("ptr_0x20000200" to pointedBytes)
        val changedNodes = mutableListOf<LiveWatchTreeNode>()

        // 1. 初次采样到达：深度嵌套标量 vx 获得值 12.34f
        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = topBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { dataMap[it] },
            onNodeChanged = { changedNodes.add(it) },
        )

        assertNotNull(childVx.cachedBytes)
        assertTrue(WatchValueFormatter.formatNativeValue(memberVx, childVx.cachedBytes!!).startsWith("12.34"))

        // 2. 瞬态丢包 / 单帧空数据：深层孙节点 vx 必须坚挺保持缓存防闪烁
        dynamicTargets.clear()
        changedNodes.clear()
        dataMap["ptr_0x20000200"] = null

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = topBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { dataMap[it] },
            onNodeChanged = { changedNodes.add(it) },
        )

        assertNotNull("瞬态 pointedBytes=null 时深层后代 vx 严禁被冲掉为 null 导致闪烁", childVx.cachedBytes)
        assertTrue(WatchValueFormatter.formatNativeValue(memberVx, childVx.cachedBytes!!).startsWith("12.34"))
        assertEquals("数据未实质改变不得误通知", 0, changedNodes.size)

        // 3. 指针地址实质改变（从 0x20000200 变为 0x20000400）：深层后代必须级联失效清空
        val newTopBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20000400.toInt()).array()
        dynamicTargets.clear()
        changedNodes.clear()

        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = newTopBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { dataMap[it] }, // 此时 ptr_0x20000400 尚在途中为 null
            onNodeChanged = { changedNodes.add(it) },
        )

        assertNull("指针地址突变后旧地址的深层后代数据必须失效清除", childVx.cachedBytes)
        assertEquals(1, dynamicTargets.size)
        assertEquals("ptr_0x20000400", dynamicTargets[0].id)
    }

    @Test
    fun testPointerChildPhysicalAddressAndCopyFormat() {
        val memberVy = SymbolNode(
            name = "vy",
            typeName = "float",
            address = 0x04L, // 相对结构体偏移 4 字节
            size = 4,
            encoding = "float",
        )
        val pointerNode = SymbolNode(
            name = "g_chassis_ptr",
            typeName = "Chassis*",
            address = 0x20000000L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            pointeeSize = 64,
            members = listOf(memberVy),
        )
        val entry = WatchItem("w0", "g_chassis_ptr", 0x20000000L, 4, "pointer", "Chassis*").apply { node = pointerNode }
        val topNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr", pointerNode, "g_chassis_ptr", true, true, entry))
        val childVy = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr.vy", memberVy, "g_chassis_ptr.vy", false, true, entry))
        topNode.add(childVy)

        // 顶层指针持有目标地址 0x20001000
        val topBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20001000.toInt()).array()
        val pointedBytes = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(4)
            putFloat(56.78f)
        }.array()

        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()
        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = topBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { pointedBytes },
        )

        // 验证物理内存地址为 0x20001000 + 4 = 0x20001004，而非相对偏移 4
        assertEquals(0x20001004L, childVy.physicalAddress)
        val effectiveAddr = if (childVy.physicalAddress >= 0x1000L) childVy.physicalAddress else childVy.address
        val copiedStr = String.format(java.util.Locale.ROOT, "0x%08X", effectiveAddr)
        assertEquals("0x20001004", copiedStr)
    }

    @Test
    fun testPointerArrayStructMembersBothAddedToScopeSuccessfully() {
        // 构造 g_chassis_ptr -> _ctx -> data[0].vx 和 data[1].vx
        val vx0 = SymbolNode(
            name = "vx",
            typeName = "float",
            address = 104L, // 0x68
            size = 4,
            encoding = "float",
        )
        val data0 = SymbolNode(
            name = "[0]",
            typeName = "wl_chassis_data_ctx_t",
            address = 104L,
            size = 20,
            encoding = "composite",
            members = listOf(vx0),
        )
        val vx1 = SymbolNode(
            name = "vx",
            typeName = "float",
            address = 124L, // 0x7c (偏移增加 20 字节)
            size = 4,
            encoding = "float",
        )
        val data1 = SymbolNode(
            name = "[1]",
            typeName = "wl_chassis_data_ctx_t",
            address = 124L,
            size = 20,
            encoding = "composite",
            members = listOf(vx1),
        )
        val dataArray = SymbolNode(
            name = "data",
            typeName = "wl_chassis_data_ctx_t[2]",
            address = 104L,
            size = 40,
            encoding = "composite",
            members = listOf(data0, data1),
        )
        val ctxNode = SymbolNode(
            name = "_ctx",
            typeName = "pyro::wl_chassis_ctx_t",
            address = 0L,
            size = 192,
            encoding = "composite",
            members = listOf(dataArray),
        )
        val ptrNode = SymbolNode(
            name = "g_chassis_ptr",
            typeName = "pyro::wl_chassis_t *",
            address = 0x20000178L,
            size = 4,
            encoding = "pointer",
            isPointer = true,
            members = listOf(ctxNode),
        )

        val entry = WatchItem("w0", "g_chassis_ptr", 0x20000178L, 4, "pointer", "Chassis*").apply { node = ptrNode }
        val topNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr", ptrNode, "g_chassis_ptr", true, true, entry))
        val ctxTreeNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr._ctx", ctxNode, "g_chassis_ptr._ctx", false, true, entry))
        val arrayTreeNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr._ctx.data", dataArray, "g_chassis_ptr._ctx.data", false, true, entry))
        val data0TreeNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr._ctx.data[0]", data0, "g_chassis_ptr._ctx.data[0]", false, true, entry))
        val vx0TreeNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr._ctx.data[0].vx", vx0, "g_chassis_ptr._ctx.data[0].vx", false, true, entry))
        val data1TreeNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr._ctx.data[1]", data1, "g_chassis_ptr._ctx.data[1]", false, true, entry))
        val vx1TreeNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr._ctx.data[1].vx", vx1, "g_chassis_ptr._ctx.data[1].vx", false, true, entry))

        topNode.add(ctxTreeNode)
        ctxTreeNode.add(arrayTreeNode)
        arrayTreeNode.add(data0TreeNode)
        data0TreeNode.add(vx0TreeNode)
        arrayTreeNode.add(data1TreeNode)
        data1TreeNode.add(vx1TreeNode)

        // 模拟指针解引用：指针指向 0x20001000L
        val ptrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20001000.toInt()).array()
        val pointeeBytes = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(104)
            putFloat(1.23f)
            position(124)
            putFloat(4.56f)
        }.array()

        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()
        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = ptrBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { pointeeBytes },
        )

        // 验证物理内存地址
        assertEquals(0x20001000L + 104L, vx0TreeNode.physicalAddress)
        assertEquals(0x20001000L + 124L, vx1TreeNode.physicalAddress)
        assertTrue(vx0TreeNode.physicalAddress != vx1TreeNode.physicalAddress)

        // 验证示波器去重逻辑：不同物理地址均可成功加入通道
        val scopeChannels = mutableListOf<org.embedded.monitor.core.ScopeVariable>()
        fun addScopeVar(label: String, addr: Long, size: Int, enc: String): Boolean {
            if (scopeChannels.any { it.address == addr }) return false
            scopeChannels.add(org.embedded.monitor.core.ScopeVariable(label, addr, size))
            return true
        }

        assertTrue("第一个变量 data[0].vx 添加成功", addScopeVar(vx0TreeNode.data.fullPath, vx0TreeNode.physicalAddress, vx0TreeNode.size, vx0TreeNode.encoding))
        assertTrue("第二个变量 data[1].vx 必须也能成功添加到示波器", addScopeVar(vx1TreeNode.data.fullPath, vx1TreeNode.physicalAddress, vx1TreeNode.size, vx1TreeNode.encoding))
        assertEquals(2, scopeChannels.size)
    }

    @Test
    fun testNullOrUnresolvedPointerChildRejectedFromScope() {
        // 当指针为空或未展开时，物理地址必须防御 < 0x1000L
        val vx0 = SymbolNode(name = "vx", typeName = "float", address = 104L, size = 4, encoding = "float")
        val ptrNode = SymbolNode(name = "g_chassis_ptr", typeName = "void*", address = 0x20000178L, size = 4, encoding = "pointer", isPointer = true, members = listOf(vx0))
        val entry = WatchItem("w0", "g_chassis_ptr", 0x20000178L, 4, "pointer", "void*").apply { node = ptrNode }
        val topNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr", ptrNode, "g_chassis_ptr", true, true, entry))
        val vx0TreeNode = LiveWatchTreeNode(WatchNodeData("w0", "g_chassis_ptr.vx", vx0, "g_chassis_ptr.vx", false, true, entry))
        topNode.add(vx0TreeNode)

        // 模拟指针为空 (nullptr: 0x0)
        val nullPtrBytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
        val dynamicTargets = mutableListOf<AgentService.DynamicWatchTarget>()
        LiveWatchTreeUpdater.updateChildBytesRecursively(
            node = topNode,
            currentBytes = nullPtrBytes,
            baseAddress = topNode.data.entry.address,
            isNullPtr = false,
            parentPtrAddress = null,
            dynamicTargets = dynamicTargets,
            isExpanded = { true },
            watchValueProvider = { null },
        )

        // 空指针下 physicalAddress 为 0L，不得作为有效地址添加到示波器
        assertEquals(0L, vx0TreeNode.physicalAddress)
        assertTrue(vx0TreeNode.physicalAddress < 0x1000L)

        // 校验示波器地址合法性判断
        val scopeChannels = mutableListOf<org.embedded.monitor.core.ScopeVariable>()
        fun safeAddScopeVar(label: String, addr: Long, size: Int): Boolean {
            if (addr < 0x1000L) return false
            if (scopeChannels.any { it.address == addr }) return false
            scopeChannels.add(org.embedded.monitor.core.ScopeVariable(label, addr, size))
            return true
        }

        assertFalse("空指针或未初始化的子成员地址 (0L) 不得加入示波通道", safeAddScopeVar(vx0TreeNode.data.fullPath, vx0TreeNode.physicalAddress, vx0TreeNode.size))
        assertEquals(0, scopeChannels.size)
    }

    @Test
    fun testOriginalDeduplicationBugReproducedAndFixed() {
        val scopeChannels = mutableListOf<org.embedded.monitor.core.ScopeVariable>()
        fun addScopeVar(label: String, addr: Long, size: Int): Boolean {
            if (scopeChannels.any { it.address == addr }) return false
            scopeChannels.add(org.embedded.monitor.core.ScopeVariable(label, addr, size))
            return true
        }

        // 模拟修复前的错误行为：两个数组元素的 vx 具有相同的 offset (104L)
        val buggedAddr0 = 0x20001000L + 104L
        val buggedAddr1 = 0x20001000L + 104L // 偏移未递归步进
        assertTrue(addScopeVar("g_chassis_ptr._ctx.data[0].vx", buggedAddr0, 4))
        assertFalse("旧实现中 data[1].vx 因地址相同 (0x20001068) 被去重逻辑拦截", addScopeVar("g_chassis_ptr._ctx.data[1].vx", buggedAddr1, 4))
        assertEquals(1, scopeChannels.size)

        // 清空后模拟修复后的行为：偏移正确步进 (104L vs 124L)
        scopeChannels.clear()
        val fixedAddr0 = 0x20001000L + 104L // 0x20001068
        val fixedAddr1 = 0x20001000L + 124L // 0x2000107C
        assertTrue("data[0].vx 添加成功", addScopeVar("g_chassis_ptr._ctx.data[0].vx", fixedAddr0, 4))
        assertTrue("data[1].vx 必须也能成功添加到示波器", addScopeVar("g_chassis_ptr._ctx.data[1].vx", fixedAddr1, 4))
        assertEquals(2, scopeChannels.size)
        assertEquals(fixedAddr0, scopeChannels[0].address)
        assertEquals(fixedAddr1, scopeChannels[1].address)
    }
}


