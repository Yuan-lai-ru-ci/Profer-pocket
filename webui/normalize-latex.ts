/**
 * 把 LaTeX 原生分隔符规范化为 remark-math 默认识别的 dollar 形式。
 *
 * 背景：模型常按 LaTeX 习惯输出行内 \( ... \) 或块级 \[ ... \]，
 * 但 CommonMark 会把反斜杠当转义吃掉，留下裸括号，remark-math 识别不到。
 * 桌面端同款逻辑（renderer/lib/normalize-latex.ts），这里移植到 WebUI。
 *
 * 策略：markdown 解析前替换；代码块与行内代码里的字面量要保护，避免误改。
 */
const PLACEHOLDER = (index: number): string => `PROFER_LATEX_PROTECT_${index}`
const PLACEHOLDER_PATTERN = /PROFER_LATEX_PROTECT_(\d+)/g

export function normalizeLatexDelimiters(text: string): string {
  if (!text || (!text.includes('\\(') && !text.includes('\\['))) {
    return text
  }

  const protectedSegments: string[] = []
  const protect = (segment: string): string => PLACEHOLDER(protectedSegments.push(segment) - 1)

  let working = text.replace(/```[\s\S]*?```/g, protect)
  working = working.replace(/`[^`\n]*`/g, protect)

  working = working.replace(/\\\[([\s\S]+?)\\\]/g, (_match, inner: string) => `$$${inner}$$`)
  working = working.replace(/\\\(([\s\S]+?)\\\)/g, (_match, inner: string) => `$${inner}$`)

  working = working.replace(PLACEHOLDER_PATTERN, (_match, index: string) =>
    protectedSegments[Number(index)] ?? _match)

  return working
}
