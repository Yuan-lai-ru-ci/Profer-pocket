/**
 * `/goal` 命令解析（WebUI 侧镜像）。
 *
 * 桌面端的唯一实现是 `ProferAI/packages/shared/src/utils/goal-contract.ts` 的
 * `parseGoalCommand` —— 它是 renderer 与 main 共用的。WebUI 用的是 pocket 那份
 * 滞后快照（不含 Goal 相关工具函数），所以按「取能力不搬文件」的既有做法在页面侧镜像一份，
 * 语法必须与主仓保持一致：
 *
 *   /goal 完成登录页
 *   @verify: pnpm test:e2e login 通过
 *   @constraint: 不修改支付相关代码
 *   @stop: 需要生产环境凭据时
 *
 * 契约标记行不进入 goal 文本本身；每类标记取第一条 ≤240 字的有效行。
 * 主仓改语法时这里要一起改（`goal-command.test.ts` 钉住了这些用例）。
 */
import { GOAL_CONTRACT_MAX_CHARS, type GoalContract } from './goal-model'

const GOAL_SUBCOMMANDS = ['status', 'pause', 'resume', 'stop', 'clear'] as const

export type GoalSubcommand = (typeof GOAL_SUBCOMMANDS)[number]

export type GoalCommand =
  | { type: 'not_goal' }
  | { type: 'invalid'; reason: string }
  | { type: GoalSubcommand }
  | { type: 'start'; goal: string; contract?: GoalContract }

/**
 * 从 `/goal` 正文中分离目标文本与契约标记。
 *
 * 与主仓逐行一致：标记行被摘掉且**不进入**目标文本；超过长度上限的标记行按普通正文保留
 * （用户写的长句不该被静默丢弃）；同一类标记只取第一条。
 */
export function parseGoalContractInput(text: string): { goal: string; contract?: GoalContract } {
  const goalLines: string[] = []
  const contract: GoalContract = {}
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim()
    const match = line.match(/^@(verify|constraint|constraints|stop):\s*(\S.*)$/i)
    if (match) {
      const value = match[2]!.trim()
      if (value.length <= GOAL_CONTRACT_MAX_CHARS) {
        const kind = match[1]!.toLowerCase()
        if (kind === 'verify' && !contract.verification) contract.verification = value
        else if (kind.startsWith('constraint') && !contract.constraints) contract.constraints = value
        else if (kind === 'stop' && !contract.stopWhen) contract.stopWhen = value
        // 只有真正采纳为契约才吃掉这一行
        continue
      }
      // 超长标记行不当作契约，但也不能静默丢弃 —— 退回成目标正文
    }
    goalLines.push(rawLine)
  }
  const goal = goalLines.join('\n').trim()
  const hasContract = Boolean(contract.verification || contract.constraints || contract.stopWhen)
  return { goal, ...(hasContract ? { contract } : {}) }
}

export function parseGoalCommand(input: string): GoalCommand {
  const trimmed = input.trim()
  // 必须以 /goal 开头且后面是空白或结束：`/goalkeeper` 不是命令
  if (!/^\/goal(?:\s|$)/i.test(trimmed)) return { type: 'not_goal' }
  const rest = trimmed.replace(/^\/goal\s*/i, '').trim()
  if (!rest) return { type: 'invalid', reason: '目标不能为空，例如：/goal 完成登录页' }
  const single = rest.toLowerCase()
  // 子命令只在单行时生效：多行说明用户在写目标（可能第一行恰好叫 status）
  if (!rest.includes('\n') && (GOAL_SUBCOMMANDS as readonly string[]).includes(single)) {
    return { type: single as GoalSubcommand }
  }
  // 单个英文单词但不是已知子命令 → 明确报错，而不是当成目标（防手滑）
  if (/^[a-z]+$/.test(single)) {
    return { type: 'invalid', reason: `未知的 Goal 命令：${rest}` }
  }
  const { goal, contract } = parseGoalContractInput(rest)
  if (!goal) return { type: 'invalid', reason: '目标不能为空，契约标记之外需要一行目标描述' }
  return { type: 'start', goal, ...(contract ? { contract } : {}) }
}
