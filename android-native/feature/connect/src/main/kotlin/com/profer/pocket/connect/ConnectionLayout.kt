package com.profer.pocket.connect

/**
 * 连接页的区块编排。
 *
 * 硬约束：**任何阶段都必须有「添加设备」区块**。扫码只是其中一种方式，
 * 手动地址 + Token 在已连接时同样要能到达；已绑定的设备列表也不能只在断线时出现，
 * 否则换第二台电脑就只剩「先断开再扫码」一条路。
 *
 * 这里只描述顺序与默认展开状态，不涉及绘制；[ConnectionScreen] 按同一份列表渲染，
 * 保证测试断言的就是实际排版。
 */
internal enum class ConnectionSection {
    /** 当前链路状态：已连接 / 未连接 / 连接中 / 失败。 */
    Status,

    /** 添加设备：扫码或手动输入，两入口并列。 */
    AddDevice,

    /** 已绑定设备列表：切换、移除。 */
    Devices,

    /** 每个端点的失败记录，仅排障时出现。 */
    EndpointFailures,
}

internal object ConnectionLayout {

    fun sections(phase: ConnectionPhase): List<ConnectionSection> = when (phase) {
        // 已连接时先给设备列表：换机、切回旧设备是这一页最常做的事。
        // 不再单列「连接详情」：网络范围设备行已有，协议是内部实现细节，
        // 「最后同步」永远显示 —，与状态卡重复。
        ConnectionPhase.Connected -> listOf(
            ConnectionSection.Status,
            ConnectionSection.Devices,
            ConnectionSection.AddDevice,
        )

        // 未连接/失败时先给添加入口：此刻用户的目标是「连上某台电脑」。
        ConnectionPhase.Disconnected -> listOf(
            ConnectionSection.Status,
            ConnectionSection.AddDevice,
            ConnectionSection.Devices,
        )

        ConnectionPhase.Failed -> listOf(
            ConnectionSection.Status,
            ConnectionSection.AddDevice,
            ConnectionSection.Devices,
            ConnectionSection.EndpointFailures,
        )

        // 连接中只保留状态与取消：中途换设备会让「取消连接」指向不明。
        ConnectionPhase.Connecting -> listOf(ConnectionSection.Status)
    }

    /** 未连接/失败时手动表单直接展开：用户来这页就是要改地址或补 Token。 */
    fun manualExpandedByDefault(phase: ConnectionPhase): Boolean = phase != ConnectionPhase.Connected
}

internal object ConnectionPaste {

    /**
     * 剪贴板内容是否是配对二维码的 JSON（`{"v":2,...}` / `{"kind":"profer-pairing",...}`）。
     *
     * 电脑端二维码可以被截图转发、也可能贴进聊天窗口；粘贴配对信息应该等同于扫码，
     * 而不是被当成「地址格式不正确」。
     */
    fun isPairingPayload(text: String): Boolean = text.trimStart().startsWith("{")
}
