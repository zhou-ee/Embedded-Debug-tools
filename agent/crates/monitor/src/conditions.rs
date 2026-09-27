//! 条件断点表达式求值器（移植原版 core/conditions.py 的受限语法）。
//!
//! 支持：整数/浮点/布尔字面量、上下文变量（寄存器 r0..r12/sp/lr/pc/xpsr 与符号）、
//! 算术 + - * / // %、位运算 & | ^ << >> ~、比较（可链式）、布尔 and/or/not（兼容 && || !）。
//! 禁止：赋值、调用、字符串、下标、属性访问。

use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq)]
enum Tok {
    Num(f64),
    Ident(usize), // 索引到 names
    LParen,
    RParen,
    Or,
    And,
    Not,
    Eq,
    Ne,
    Le,
    Ge,
    Lt,
    Gt,
    Add,
    Sub,
    Mul,
    Div,
    FloorDiv,
    Mod,
    BitOr,
    BitXor,
    BitAnd,
    Shl,
    Shr,
    BitNot,
}

struct Lexer {
    tokens: Vec<Tok>,
    names: Vec<String>,
}

fn lex(src: &str) -> Result<Lexer, String> {
    let mut tokens = Vec::new();
    let mut names: Vec<String> = Vec::new();
    let chars: Vec<char> = src.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        match c {
            ' ' | '\t' | '\r' | '\n' => i += 1,
            '(' => {
                tokens.push(Tok::LParen);
                i += 1;
            }
            ')' => {
                tokens.push(Tok::RParen);
                i += 1;
            }
            '|' => {
                if chars.get(i + 1) == Some(&'|') {
                    tokens.push(Tok::Or);
                    i += 2;
                } else {
                    tokens.push(Tok::BitOr);
                    i += 1;
                }
            }
            '&' => {
                if chars.get(i + 1) == Some(&'&') {
                    tokens.push(Tok::And);
                    i += 2;
                } else {
                    tokens.push(Tok::BitAnd);
                    i += 1;
                }
            }
            '^' => {
                tokens.push(Tok::BitXor);
                i += 1;
            }
            '~' => {
                tokens.push(Tok::BitNot);
                i += 1;
            }
            '!' => {
                if chars.get(i + 1) == Some(&'=') {
                    tokens.push(Tok::Ne);
                    i += 2;
                } else {
                    tokens.push(Tok::Not);
                    i += 1;
                }
            }
            '=' => {
                if chars.get(i + 1) == Some(&'=') {
                    tokens.push(Tok::Eq);
                    i += 2;
                } else {
                    return Err("不支持赋值 =（请用 ==）".into());
                }
            }
            '<' => {
                if chars.get(i + 1) == Some(&'=') {
                    tokens.push(Tok::Le);
                    i += 2;
                } else if chars.get(i + 1) == Some(&'<') {
                    tokens.push(Tok::Shl);
                    i += 2;
                } else {
                    tokens.push(Tok::Lt);
                    i += 1;
                }
            }
            '>' => {
                if chars.get(i + 1) == Some(&'=') {
                    tokens.push(Tok::Ge);
                    i += 2;
                } else if chars.get(i + 1) == Some(&'>') {
                    tokens.push(Tok::Shr);
                    i += 2;
                } else {
                    tokens.push(Tok::Gt);
                    i += 1;
                }
            }
            '+' => {
                tokens.push(Tok::Add);
                i += 1;
            }
            '-' => {
                tokens.push(Tok::Sub);
                i += 1;
            }
            '*' => {
                tokens.push(Tok::Mul);
                i += 1;
            }
            '/' => {
                if chars.get(i + 1) == Some(&'/') {
                    tokens.push(Tok::FloorDiv);
                    i += 2;
                } else {
                    tokens.push(Tok::Div);
                    i += 1;
                }
            }
            '%' => {
                tokens.push(Tok::Mod);
                i += 1;
            }
            '0'..='9' | '.' => {
                let start = i;
                if c == '0' && matches!(chars.get(i + 1), Some('x') | Some('X')) {
                    i += 2;
                    while i < chars.len() && chars[i].is_ascii_hexdigit() {
                        i += 1;
                    }
                    let hex: String = chars[start + 2..i].iter().collect();
                    if hex.is_empty() {
                        return Err("无效的十六进制数".into());
                    }
                    let v = u64::from_str_radix(&hex, 16).map_err(|e| e.to_string())?;
                    tokens.push(Tok::Num(v as f64));
                } else {
                    while i < chars.len() && (chars[i].is_ascii_digit() || chars[i] == '.') {
                        i += 1;
                    }
                    let text: String = chars[start..i].iter().collect();
                    let v: f64 = text.parse().map_err(|_| format!("无效数字: {text}"))?;
                    tokens.push(Tok::Num(v));
                }
            }
            c if c.is_alphabetic() || c == '_' => {
                let start = i;
                while i < chars.len() && (chars[i].is_alphanumeric() || chars[i] == '_') {
                    i += 1;
                }
                let word: String = chars[start..i].iter().collect();
                match word.as_str() {
                    "and" => tokens.push(Tok::And),
                    "or" => tokens.push(Tok::Or),
                    "not" => tokens.push(Tok::Not),
                    "true" | "True" => tokens.push(Tok::Num(1.0)),
                    "false" | "False" => tokens.push(Tok::Num(0.0)),
                    _ => {
                        names.push(word);
                        tokens.push(Tok::Ident(names.len() - 1));
                    }
                }
            }
            other => return Err(format!("不支持的字符: {other}")),
        }
    }
    Ok(Lexer { tokens, names })
}

struct Parser<'a> {
    tokens: &'a [Tok],
    names: &'a [String],
    ctx: &'a HashMap<String, f64>,
    pos: usize,
}

impl<'a> Parser<'a> {
    fn peek(&self) -> Option<Tok> {
        self.tokens.get(self.pos).copied()
    }
    fn next(&mut self) -> Option<Tok> {
        let t = self.peek();
        if t.is_some() {
            self.pos += 1;
        }
        t
    }
    fn expect(&mut self, tok: Tok) -> Result<(), String> {
        if self.next() == Some(tok) {
            Ok(())
        } else {
            Err("语法错误".into())
        }
    }

    fn parse_or(&mut self) -> Result<f64, String> {
        let mut left = self.parse_and()?;
        while self.peek() == Some(Tok::Or) {
            self.next();
            let right = self.parse_and()?;
            left = if truthy(left) || truthy(right) { 1.0 } else { 0.0 };
        }
        Ok(left)
    }

    fn parse_and(&mut self) -> Result<f64, String> {
        let mut left = self.parse_not()?;
        while self.peek() == Some(Tok::And) {
            self.next();
            let right = self.parse_not()?;
            left = if truthy(left) && truthy(right) { 1.0 } else { 0.0 };
        }
        Ok(left)
    }

    fn parse_not(&mut self) -> Result<f64, String> {
        if self.peek() == Some(Tok::Not) {
            self.next();
            let v = self.parse_not()?;
            return Ok(if truthy(v) { 0.0 } else { 1.0 });
        }
        self.parse_comparison()
    }

    /// 链式比较：a < b <= c
    fn parse_comparison(&mut self) -> Result<f64, String> {
        let first = self.parse_bitor()?;
        let mut prev = first;
        let mut result: Option<bool> = None;
        loop {
            let op = match self.peek() {
                Some(t @ (Tok::Eq | Tok::Ne | Tok::Le | Tok::Ge | Tok::Lt | Tok::Gt)) => t,
                _ => break,
            };
            self.next();
            let right = self.parse_bitor()?;
            let ok = match op {
                Tok::Eq => prev == right,
                Tok::Ne => prev != right,
                Tok::Le => prev <= right,
                Tok::Ge => prev >= right,
                Tok::Lt => prev < right,
                Tok::Gt => prev > right,
                _ => unreachable!(),
            };
            result = Some(result.unwrap_or(true) && ok);
            prev = right;
        }
        Ok(match result {
            Some(b) => {
                if b {
                    1.0
                } else {
                    0.0
                }
            }
            None => first,
        })
    }

    fn parse_bitor(&mut self) -> Result<f64, String> {
        let mut left = self.parse_bitxor()?;
        while self.peek() == Some(Tok::BitOr) {
            self.next();
            let right = self.parse_bitxor()?;
            left = ((to_i64(left)?) | (to_i64(right)?)) as f64;
        }
        Ok(left)
    }

    fn parse_bitxor(&mut self) -> Result<f64, String> {
        let mut left = self.parse_bitand()?;
        while self.peek() == Some(Tok::BitXor) {
            self.next();
            let right = self.parse_bitand()?;
            left = ((to_i64(left)?) ^ (to_i64(right)?)) as f64;
        }
        Ok(left)
    }

    fn parse_bitand(&mut self) -> Result<f64, String> {
        let mut left = self.parse_shift()?;
        while self.peek() == Some(Tok::BitAnd) {
            self.next();
            let right = self.parse_shift()?;
            left = ((to_i64(left)?) & (to_i64(right)?)) as f64;
        }
        Ok(left)
    }

    fn parse_shift(&mut self) -> Result<f64, String> {
        let mut left = self.parse_addsub()?;
        loop {
            match self.peek() {
                Some(Tok::Shl) => {
                    self.next();
                    let right = self.parse_addsub()?;
                    let shift = to_i64(right)?.clamp(0, 63) as u32;
                    left = ((to_i64(left)?) << shift) as f64;
                }
                Some(Tok::Shr) => {
                    self.next();
                    let right = self.parse_addsub()?;
                    let shift = to_i64(right)?.clamp(0, 63) as u32;
                    left = ((to_i64(left)?) >> shift) as f64;
                }
                _ => break,
            }
        }
        Ok(left)
    }

    fn parse_addsub(&mut self) -> Result<f64, String> {
        let mut left = self.parse_muldiv()?;
        loop {
            match self.peek() {
                Some(Tok::Add) => {
                    self.next();
                    left += self.parse_muldiv()?;
                }
                Some(Tok::Sub) => {
                    self.next();
                    left -= self.parse_muldiv()?;
                }
                _ => break,
            }
        }
        Ok(left)
    }

    fn parse_muldiv(&mut self) -> Result<f64, String> {
        let mut left = self.parse_unary()?;
        loop {
            match self.peek() {
                Some(Tok::Mul) => {
                    self.next();
                    left *= self.parse_unary()?;
                }
                Some(Tok::Div) => {
                    self.next();
                    let right = self.parse_unary()?;
                    if right == 0.0 {
                        return Err("除以零".into());
                    }
                    left /= right;
                }
                Some(Tok::FloorDiv) => {
                    self.next();
                    let right = self.parse_unary()?;
                    if right == 0.0 {
                        return Err("除以零".into());
                    }
                    left = (left / right).floor();
                }
                Some(Tok::Mod) => {
                    self.next();
                    let right = self.parse_unary()?;
                    if right == 0.0 {
                        return Err("除以零".into());
                    }
                    left %= right;
                }
                _ => break,
            }
        }
        Ok(left)
    }

    fn parse_unary(&mut self) -> Result<f64, String> {
        match self.peek() {
            Some(Tok::Sub) => {
                self.next();
                Ok(-self.parse_unary()?)
            }
            Some(Tok::Add) => {
                self.next();
                self.parse_unary()
            }
            Some(Tok::BitNot) => {
                self.next();
                let v = self.parse_unary()?;
                Ok(!(to_i64(v)?) as f64)
            }
            _ => self.parse_primary(),
        }
    }

    fn parse_primary(&mut self) -> Result<f64, String> {
        match self.next() {
            Some(Tok::Num(v)) => Ok(v),
            Some(Tok::Ident(idx)) => {
                let name = &self.names[idx];
                self.ctx
                    .get(name)
                    .copied()
                    .ok_or_else(|| format!("未知标识符: {name}"))
            }
            Some(Tok::LParen) => {
                let v = self.parse_or()?;
                self.expect(Tok::RParen)?;
                Ok(v)
            }
            _ => Err("语法错误：期望表达式".into()),
        }
    }
}

fn truthy(v: f64) -> bool {
    v != 0.0
}

fn to_i64(v: f64) -> Result<i64, String> {
    if !v.is_finite() {
        return Err("位运算的操作数非法".into());
    }
    Ok(v as i64)
}

/// 求值条件表达式，返回布尔结果。
pub fn evaluate(source: &str, ctx: &HashMap<String, f64>) -> Result<bool, String> {
    let src = source.trim();
    if src.is_empty() {
        return Ok(true);
    }
    if src.len() > 512 {
        return Err("条件表达式过长".into());
    }
    let lexer = lex(src)?;
    let mut parser = Parser {
        tokens: &lexer.tokens,
        names: &lexer.names,
        ctx,
        pos: 0,
    };
    let value = parser.parse_or()?;
    if parser.pos != lexer.tokens.len() {
        return Err("语法错误：存在多余内容".into());
    }
    Ok(truthy(value))
}

/// 仅校验语法与引用（在设置断点时提前反馈）。
pub fn validate(source: &str, known_names: &dyn Fn(&str) -> bool) -> Result<(), String> {
    let src = source.trim();
    if src.is_empty() {
        return Ok(());
    }
    let lexer = lex(src)?;
    for name in &lexer.names {
        if !known_names(name) {
            return Err(format!("未知标识符: {name}"));
        }
    }
    Ok(())
}

/// 从内存字节解码标量（小端），供条件上下文中的符号取值。
pub fn decode_scalar(bytes: &[u8], signed: bool) -> f64 {
    let mut raw: u64 = 0;
    for (i, b) in bytes.iter().take(8).enumerate() {
        raw |= (*b as u64) << (8 * i);
    }
    let bits = bytes.len().min(8) * 8;
    if signed && bits > 0 && bits < 64 {
        let sign_bit = 1u64 << (bits - 1);
        if raw & sign_bit != 0 {
            let extended = raw | !((1u64 << bits) - 1);
            return extended as i64 as f64;
        }
    }
    if signed {
        raw as i64 as f64
    } else {
        raw as f64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ctx() -> HashMap<String, f64> {
        let mut m = HashMap::new();
        m.insert("r0".into(), 3.0);
        m.insert("r1".into(), 0xff as f64);
        m.insert("my_flag".into(), 1.0);
        m.insert("speed".into(), -20.0);
        m
    }

    #[test]
    fn simple_eq() {
        assert!(evaluate("r0 == 3", &ctx()).unwrap());
        assert!(!evaluate("r0 == 4", &ctx()).unwrap());
    }

    #[test]
    fn bit_ops() {
        assert!(evaluate("(r1 & 0x0f) == 0x0f", &ctx()).unwrap());
        assert!(evaluate("r1 >> 4 == 0x0f", &ctx()).unwrap());
    }

    #[test]
    fn bool_ops() {
        assert!(evaluate("r0 == 3 and my_flag == 1", &ctx()).unwrap());
        assert!(evaluate("r0 == 9 || my_flag", &ctx()).unwrap());
        assert!(evaluate("not (r0 == 9)", &ctx()).unwrap());
        assert!(evaluate("!0", &ctx()).unwrap());
    }

    #[test]
    fn chained_compare() {
        assert!(evaluate("0 < r0 < 4", &ctx()).unwrap());
        assert!(!evaluate("0 < r0 < 3", &ctx()).unwrap());
    }

    #[test]
    fn signed_symbol() {
        assert!(evaluate("speed < 0", &ctx()).unwrap());
    }

    #[test]
    fn reject_assignment() {
        assert!(evaluate("r0 = 3", &ctx()).is_err());
    }

    #[test]
    fn reject_unknown() {
        assert!(evaluate("unknown_var == 1", &ctx()).is_err());
    }

    #[test]
    fn reject_call() {
        assert!(evaluate("foo(1)", &ctx()).is_err());
    }

    #[test]
    fn decode_signed() {
        assert_eq!(decode_scalar(&[0xec, 0xff], true), -20.0);
        assert_eq!(decode_scalar(&[0xec, 0xff], false), 65516.0);
    }

    #[test]
    fn hex_literal() {
        assert!(evaluate("r1 == 0xFF", &ctx()).unwrap());
    }
}
