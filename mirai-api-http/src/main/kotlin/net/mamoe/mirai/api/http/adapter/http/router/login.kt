/*
 * Copyright 2023 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.api.http.adapter.http.router

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.mamoe.mirai.Bot
import net.mamoe.mirai.api.http.adapter.internal.dto.*
import net.mamoe.mirai.console.MiraiConsole
import net.mamoe.mirai.console.events.AutoLoginEvent
import net.mamoe.mirai.console.internal.data.builtins.AutoLoginConfig
import net.mamoe.mirai.console.rootDir
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.event.broadcast
import net.mamoe.mirai.utils.*

/**
 * 配置路由
 */
@OptIn(ConsoleExperimentalApi::class, MiraiInternalApi::class)
internal fun Application.loginRouter() = routing {

    get("/loginSession") {
        val qq = call.parameters["qq"] ?: "0"
        val session = SessionManager.findSession(qq.toLong())
        if (session == null) {
            call.respond(LoginResult(LoginPhase.NO_SESSION.name))
            return@get
        }
        call.respond(session.toResult())
    }

    post("/loginSlideCode") {
        val loginSlideCode = call.receive<LoginSlideCode>()
        val session = SessionManager.findSession(loginSlideCode.qq)
        if (session == null) {
            call.respond(LoginResult(LoginPhase.NO_SESSION.name))
            return@post
        }
        session.sendToReqChannel(loginSlideCode.slideCode)
        session.blockWaitResp()
        call.respond(session.toResult())
    }

    post("/loginAckSMS") {
        val loginAckSMS = call.receive<LoginAckSMS>()
        val session = SessionManager.findSession(loginAckSMS.qq)
        if (session == null) {
            call.respond(LoginResult(LoginPhase.NO_SESSION.name))
            return@post
        }
        session.sendToReqChannel(loginAckSMS.ackSMS)
        session.blockWaitResp()
        call.respond(session.toResult())
    }

    post("/loginSMSCode") {
        val loginSMSCode = call.receive<LoginSMSCode>()
        val session = SessionManager.findSession(loginSMSCode.qq)
        if (session == null) {
            call.respond(LoginResult(LoginPhase.NO_SESSION.name))
            return@post
        }
        session.sendToReqChannel(loginSMSCode.smsCode)
        session.blockWaitResp()
        call.respond(session.toResult())
    }

    post("/login") {
        val loginDTO = call.receive<LoginRequest>()

        if (loginDTO.forceNew) {
            clearBotCache(loginDTO.qq)
        }

        SessionManager.findSession(loginDTO.qq)?.run {
            if (System.currentTimeMillis() - timestamp < 15_000) {
                call.respond(LoginResult(LoginPhase.EXIST_SESSION.name))
                return@post
            }
        }

        val session = SessionManager.createNewSession(loginDTO.qq)

        recoverDeviceJson(loginDTO.qq, loginDTO.deviceJson)

        fun BotConfiguration.init() {
            this.protocol = BotConfiguration.MiraiProtocol.valueOf(loginDTO.protocol)
            this.heartbeatStrategy = BotConfiguration.HeartbeatStrategy.valueOf(loginDTO.heartbeatStrategy)
            fileBasedDeviceInfo("device.json")
            this.loginSolver = PlugInHTTPLoginSolver(session)
        }

        val bot = when (AutoLoginConfig.Account.PasswordKind.valueOf(loginDTO.passwordKind)) {
            AutoLoginConfig.Account.PasswordKind.MD5 -> {
                MiraiConsole.addBot(loginDTO.qq, loginDTO.password.hexToBytes(), BotConfiguration::init)
            }

            AutoLoginConfig.Account.PasswordKind.PLAIN -> {
                MiraiConsole.addBot(loginDTO.qq, loginDTO.password, BotConfiguration::init)
            }
        }
        launch {
            runCatching {
                bot.login()
            }.onSuccess {
                session.updatePhase(LoginPhase.SUCCESS)
                session.sendToRespChannel()
                launch {
                    AutoLoginEvent.Success(bot = bot).broadcast()
                }
            }.onFailure {
                session.updatePhase(LoginPhase.FAILURE)
                session.sendToRespChannel()
                runCatching {
                    bot.close()
                }.onFailure { err ->
                    bot.logger.error("bot close error: $err")
                }
                launch {
                    AutoLoginEvent.Failure(bot = bot, cause = it).broadcast()
                }
            }
        }
        session.blockWaitResp()
        call.respond(session.toResult())
    }
}

val logger = MiraiLogger.Factory.create(LoginSession::class)

enum class LoginPhase {
    INIT, NEED_SLIDE_CODE, NEED_SEND_PHONE_CODE, NEED_PHONE_CODE, NEED_JUMP_VERIFY, SUCCESS, FAILURE, NO_SESSION, EXIST_SESSION
}

class LoginSession(val qq: Long) {
    private val reqChannel: Channel<String> = Channel(0)
    private val respChannel: Channel<String> = Channel(0)
    private var phase: LoginPhase = LoginPhase.INIT
    var timestamp: Long = System.currentTimeMillis()
    var slideUrl: String = ""
    var phoneNumber: String = ""
    var verifyUrl: String = ""

    suspend fun blockWaitReq(timeout: Long = 600_000): String {
        return withTimeout(timeout) {
            reqChannel.receive()
        }
    }

    suspend fun blockWaitResp(timeout: Long = 30_000): String {
        return withTimeout(timeout) {
            respChannel.receive()
        }
    }

    suspend fun sendToReqChannel(value: String) {
        return withTimeout(5000) {
            reqChannel.send(value)
        }
    }

    suspend fun sendToRespChannel(value: String = "OK") {
        return withTimeout(5000) {
            respChannel.send(value)
        }
    }

    fun updatePhase(phase: LoginPhase) {
        this.phase = phase
        this.timestamp = System.currentTimeMillis()
    }

    fun toResult(): LoginResult {
        return LoginResult(
            this.phase.name,
            this.slideUrl,
            this.phoneNumber,
            this.verifyUrl
        )
    }

    override fun toString(): String {
        return "LoginSession(qq=$qq, reqChannel=$reqChannel, respChannel=$respChannel, phase=$phase, timestamp=$timestamp, slideUrl='$slideUrl', phoneNumber='$phoneNumber', verifyUrl='$verifyUrl')"
    }

}

object SessionManager {

    private val sessions = mutableMapOf<Long, LoginSession>()

    init {

        Thread {
            while (true) {
                Thread.sleep(10_000)
                val now = System.currentTimeMillis()
                sessions.forEach { (_, session) ->
                    if (now - session.timestamp > 3600_000) {
                        sessions.remove(session.qq)
                    }
                }
            }
        }.start()
    }

    fun createNewSession(qq: Long): LoginSession {
        val session = LoginSession(qq)
        sessions[qq] = session
        return session
    }

    fun findSession(qq: Long): LoginSession? {
        return sessions[qq]?.also { logger.info("find session: $it") }
    }
}


class PlugInHTTPLoginSolver(val session: LoginSession) : LoginSolver() {
    override val isSliderCaptchaSupported: Boolean get() = true

    override suspend fun onSolvePicCaptcha(bot: Bot, data: ByteArray): String {
        bot.logger.info("onSolvePicCaptcha not support")
        throw RuntimeException("onSolvePicCaptcha not support")
    }

    override suspend fun onSolveSliderCaptcha(bot: Bot, url: String): String {
        bot.logger.info("onSolveSliderCaptcha: $url")
        session.updatePhase(LoginPhase.NEED_SLIDE_CODE)
        session.slideUrl = url
        session.sendToRespChannel()
        val slideCode = session.blockWaitReq()
        bot.logger.info("onSolveSliderCaptcha: $slideCode")
        return slideCode;
    }

    override suspend fun onSolveDeviceVerification(
        bot: Bot, requests: DeviceVerificationRequests
    ): DeviceVerificationResult {
        val logger = bot.logger
        requests.sms?.let { req ->
            solveSms(logger, req)?.let { return it }
        }
        requests.fallback?.let { fallback ->
            solveFallback(logger, fallback.url)
            return fallback.solved()
        }
        error("User rejected SMS login while fallback login method not available.")
    }

    private suspend fun solveSms(
        logger: MiraiLogger, request: DeviceVerificationRequests.SmsRequest
    ): DeviceVerificationResult? {
        val countryCode = request.countryCode
        val phoneNumber = request.phoneNumber
        if (countryCode != null && phoneNumber != null) {
            logger.info("一条短信验证码将发送到你的手机 (+$countryCode) $phoneNumber. 运营商可能会收取正常短信费用, 是否继续? 输入 yes 继续, 输入其他终止并尝试其他验证方式.")
            logger.info(
                "A verification code will be send to your phone (+$countryCode) $phoneNumber, which may be charged normally, do you wish to continue? Type yes to continue, type others to cancel and try other methods."
            )
        } else {
            logger.info("一条短信验证码将发送到你的手机 (无法获取到手机号码). 运营商可能会收取正常短信费用, 是否继续? 输入 yes 继续, 输入其他终止并尝试其他验证方式.")
            logger.info(
                "A verification code will be send to your phone (failed to get phone number), " + "which may be charged normally by your carrier, " + "do you wish to continue? Type yes to continue, type others to cancel and try other methods."
            )
        }
        session.updatePhase(LoginPhase.NEED_SEND_PHONE_CODE)
        if (phoneNumber != null) {
            session.phoneNumber = phoneNumber
        }
        session.sendToRespChannel()
        val answer = session.blockWaitReq()
        return if (answer.equals("yes", ignoreCase = true)) {
            logger.info("Attempting SMS verification.")
            request.requestSms()
            logger.info("Please enter code: ")
            session.updatePhase(LoginPhase.NEED_PHONE_CODE)
            session.sendToRespChannel()
            val code = session.blockWaitReq()
            logger.info("Continuing with code '$code'.")
            request.solved(code)
        } else {
            logger.info("Cancelled.")
            null
        }
    }

    private suspend fun solveFallback(
        logger: MiraiLogger, url: String
    ): String {
        logger.info { "[UnsafeLogin] 当前登录环境不安全，服务器要求账户认证。请在 QQ 浏览器打开 $url 并完成验证后输入任意字符。" }
        logger.info { "[UnsafeLogin] Account verification required by the server. Please open $url in QQ browser and complete challenge, then type anything here to submit." }
        session.updatePhase(LoginPhase.NEED_JUMP_VERIFY)
        session.verifyUrl = url
        session.sendToRespChannel()
        val answer = session.blockWaitReq()
        logger.info { "[UnsafeLogin] 正在提交中..." }
        logger.info { "[UnsafeLogin] Submitting..." }
        return answer
    }

}

fun recoverDeviceJson(qq: Long, deviceJson: String) {
    if (deviceJson.isBlank()) {
        return
    }
    val dic = MiraiConsole.rootDir
        .resolve("bots")
        .resolve(qq.toString())
        .also { it.mkdirs() }
    val format = Json {
        prettyPrint = true
    }
    dic.resolve("device.json").writeText(format.encodeToString(format.parseToJsonElement(deviceJson)));
}

fun clearBotCache(qq: Long) {
    MiraiConsole.rootDir
        .resolve("bots")
        .resolve(qq.toString())
        .resolve("cache")
        .also { it.deleteRecursively() }
}

fun String.hexToBytes(): ByteArray {
    val hexString = this;
    val result = ByteArray(hexString.length / 2)
    for (i in hexString.indices step 2) {
        val firstDigit = Character.digit(hexString[i], 16)
        val secondDigit = Character.digit(hexString[i + 1], 16)
        val byteValue = firstDigit shl 4 or secondDigit
        result[i / 2] = byteValue.toByte()
    }
    return result
}

