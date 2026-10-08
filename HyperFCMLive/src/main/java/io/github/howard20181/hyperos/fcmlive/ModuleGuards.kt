package io.github.howard20181.hyperos.fcmlive

/** 身份和用户边界：未知身份不获得写系统设置的权限。 */
internal object ModuleGuards {
    fun authorizedSender(senderUid: Int, moduleUid: Int?): Boolean =
        senderUid in setOf(0, 1000, 2000) || (moduleUid != null && senderUid == moduleUid)

    fun validUser(userId: Int): Boolean = userId in 0..21473

    fun mayWriteUser(targetUser: Int, senderUid: Int, hasCrossUserPermission: Boolean): Boolean =
        validUser(targetUser) && senderUid >= 0 &&
            (targetUser == senderUid / 100000 || senderUid == 0 || senderUid == 1000 || hasCrossUserPermission)

    fun autostartKey(packageName: String, userId: Int): String = "$userId:$packageName"
}
