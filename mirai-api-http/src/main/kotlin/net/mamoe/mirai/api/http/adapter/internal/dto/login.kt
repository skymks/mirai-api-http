/*
 * Copyright 2023 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.api.http.adapter.internal.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class LoginRequest(
    val forceNew: Boolean = false,
    val qq: Long,
    val password: String,
    val passwordKind: String,
    val protocol: String,
    val deviceJson: String = "",
    val heartbeatStrategy: String
) : DTO

@Serializable
internal data class LoginSlideCode(
    val qq: Long,
    val slideCode: String
) : DTO

@Serializable
internal data class LoginAckSMS(
    val qq: Long,
    val ackSMS: String
) : DTO

@Serializable
internal data class LoginSMSCode(
    val qq: Long,
    val smsCode: String
) : DTO

@Serializable
data class LoginResult(
    val loginPhase: String,
    var slideUrl: String = "",
    var phoneNumber: String = "",
    var verifyUrl: String = ""
) : DTO