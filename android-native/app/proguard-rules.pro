# Profer Pocket 的 R8 规则（体验包 / 未来的正式包共用）
#
# 说明：应用里唯一的反射调用在 PocketNotifications，作用于平台的 NotificationManager
# （canPostPromotedNotifications），不涉及应用内类名；其余跨进程/跨组件入口都由
# AndroidManifest 声明，AGP 会自动 keep。因此这里不保留任何应用代码，
# 只显式声明崩溃栈可读性，并给 R8 留下「改规则要跑一次体验包」的提示。

# 崩溃栈保留行号与源文件名：R8 默认会做，显式写出避免后续规则覆盖。
-keepattributes SourceFile,LineNumberTable

# 关闭对已知可选依赖的缺失警告（当前没有此类依赖，保留占位以便新增时统一处理）。
-dontwarn javax.annotation.**
