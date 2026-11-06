package uz.tuit.lms.ui.screens

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uz.tuit.lms.R
import uz.tuit.lms.data.LoginStep
import uz.tuit.lms.data.Repo
import uz.tuit.lms.ui.components.LocalSnack
import uz.tuit.lms.ui.components.LocalTr
import uz.tuit.lms.ui.components.Opt
import uz.tuit.lms.ui.components.Segmented
import uz.tuit.lms.ui.theme.LocalDark
import uz.tuit.lmsbot.service.LmsService

/**
 * Экран загрузки: эмблема ТУИТ «дышит», от неё расходятся мягкие волны, снизу — бегущий индикатор.
 * Показывается при запуске, пока проверяется сессия (и не меньше секунды, чтобы не мелькал).
 */
@Composable
fun SplashScreen() {
    val dark = LocalDark.current
    val tr = rememberInfiniteTransition(label = "splash")
    val breathe by tr.animateFloat(1f, 1.045f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "breathe")
    val wave by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = androidx.compose.animation.core.LinearEasing)), label = "wave")
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(600, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    val ring = MaterialTheme.colorScheme.primary

    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (dark) 0.35f else 0.6f)),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer { alpha = appear.value; translationY = (1 - appear.value) * 40f }) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
                // Две волны, расходящиеся от эмблемы.
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    for (k in 0..1) {
                        val f = (wave + k * 0.5f) % 1f
                        drawCircle(ring.copy(alpha = 0.22f * (1 - f)), radius = size.minDimension / 2 * (0.5f + 0.5f * f))
                    }
                }
                Surface(
                    shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 6.dp,
                    modifier = Modifier.size(112.dp).scale(breathe),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(painterResource(if (dark) R.drawable.tuit_emblem_light else R.drawable.tuit_emblem), null, Modifier.size(92.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("TUIT LMS", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(LocalTr.current("splash_sub"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 40.dp))
            Spacer(Modifier.height(28.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        }
        Text(
            "Muhammad al-Xorazmiy nomidagi TATU", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).graphicsLayer { alpha = appear.value },
        )
    }
}

private enum class Method { LMS, ONEID }
private enum class OneId { PASSWORD, MOBILE, QR }

@Composable
fun LoginScreen() {
    val t = LocalTr.current
    var method by remember { mutableStateOf(Method.LMS) }
    var oneId by remember { mutableStateOf(OneId.PASSWORD) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Surface(shape = RoundedCornerShape(36.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(108.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Image(painterResource(if (LocalDark.current) R.drawable.tuit_emblem_light else R.drawable.tuit_emblem), null, Modifier.size(84.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(t("login_title"), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(t("login_sub"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))

            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth()) {
                Segmented(
                    listOf(Opt(Method.LMS, t("method_lms")), Opt(Method.ONEID, t("method_oneid"))),
                    method, { method = it },
                )
                Spacer(Modifier.height(16.dp))
                AnimatedContent(method, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "login") { m ->
                    if (m == Method.LMS) LmsForm(onNeedOneId = { method = Method.ONEID; oneId = OneId.PASSWORD })
                    else OneIdTheme {
                        Column {
                            OneIdHeader()
                            Spacer(Modifier.height(14.dp))
                            Segmented(
                                listOf(Opt(OneId.PASSWORD, t("oneid_password")), Opt(OneId.MOBILE, t("oneid_mobile")), Opt(OneId.QR, t("oneid_qr"))),
                                oneId, { oneId = it },
                            )
                            Spacer(Modifier.height(16.dp))
                            AnimatedContent(oneId, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "oneid") { o ->
                                when (o) {
                                    OneId.PASSWORD -> OneIdPasswordForm()
                                    OneId.MOBILE -> MobileForm()
                                    OneId.QR -> QrForm()
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(t("login_note_app"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    icon: ImageVector,
    placeholder: String? = null,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = { Icon(icon, null) },
        trailingIcon = if (password) {
            { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } }
        } else null,
        visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ime, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    )
}

/** Фирменный синий OneID (id.egov.uz). */
private val OneIdBlue = Color(0xFF003399)

/** Раздел входа через OneID — в цветах OneID: кнопки, переключатель и поля синие #003399. */
@Composable
private fun OneIdTheme(content: @Composable () -> Unit) {
    val dark = LocalDark.current
    val base = MaterialTheme.colorScheme
    val scheme = if (dark) base.copy(
        primary = Color(0xFF8FA9F5), onPrimary = Color(0xFF00205F),
        primaryContainer = Color(0xFF1A2A55), onPrimaryContainer = Color(0xFFC9D6FF),
    ) else base.copy(
        primary = OneIdBlue, onPrimary = Color.White,
        primaryContainer = Color(0xFFE6ECF8), onPrimaryContainer = OneIdBlue,
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes, content = content)
}

/** Шапка как на id.egov.uz: официальный логотип и «Yagona identifikatsiya tizimi». */
@Composable
private fun OneIdHeader() {
    val t = LocalTr.current
    val dark = LocalDark.current
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.ic_oneid), "OneID",
                modifier = Modifier.height(40.dp).width(60.dp),
                colorFilter = if (dark) androidx.compose.ui.graphics.ColorFilter.tint(Color.White) else null,
            )
            Spacer(Modifier.width(14.dp))
            Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("OneID", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(t("oneid_system"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SubmitButton(text: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp)) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ErrorText(text: String?) {
    if (text == null) return
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun oneIdError(reason: String?): String {
    val t = LocalTr.current
    return when (reason) {
        "link" -> t("err_link")
        "phone" -> t("err_phone")
        "unreachable" -> t("err_unreachable")
        null, "" -> t("err_oneid")
        else -> reason
    }
}

@Composable
private fun LmsForm(onNeedOneId: () -> Unit) {
    val t = LocalTr.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var oneIdRequired by remember { mutableStateOf(false) }

    fun submit() {
        if (login.isBlank() || password.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            val r = runCatching { Repo.loginLms(login, password) }.getOrDefault(LmsService.LoginResult.ERROR)
            busy = false
            oneIdRequired = r == LmsService.LoginResult.ONEID_REQUIRED
            error = when (r) {
                LmsService.LoginResult.OK -> null
                LmsService.LoginResult.WRONG_CREDENTIALS -> t("err_credentials")
                LmsService.LoginResult.ONEID_REQUIRED -> t("err_oneid_required")
                else -> t("error_lms")
            }
        }
    }

    Column {
        Field(login, { login = it }, t("f_login"), Icons.Rounded.Person, placeholder = t("f_login_ph"))
        Field(password, { password = it }, t("f_password"), Icons.Rounded.Lock, password = true, ime = ImeAction.Go, onDone = ::submit)
        ErrorText(error)
        if (oneIdRequired) {
            FilledTonalButton(onClick = onNeedOneId, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text(t("method_oneid")) }
        }
        SubmitButton(t("sign_in"), busy, login.isNotBlank() && password.isNotEmpty(), ::submit)
    }
}

@Composable
private fun OneIdPasswordForm() {
    val t = LocalTr.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var needCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val errText = @Composable { r: String? -> oneIdError(r) }
    var reason by remember { mutableStateOf<String?>(null) }

    fun handle(step: LoginStep) {
        when (step) {
            LoginStep.NeedCode -> { needCode = true; error = null }
            is LoginStep.Error -> { reason = step.reason; error = "err" }
            else -> error = null
        }
    }

    fun submit() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val step = runCatching {
                if (needCode) Repo.oneIdConfirm(login, code) else Repo.oneIdLogin(login, password)
            }.getOrElse { LoginStep.Error(null) }
            busy = false
            handle(step)
        }
    }

    Column {
        if (!needCode) {
            Field(login, { login = it }, t("f_oneid_login"), Icons.Rounded.Badge)
            Field(password, { password = it }, t("f_password"), Icons.Rounded.Lock, password = true, ime = ImeAction.Go, onDone = ::submit)
        } else {
            Text(t("code_2fa"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Field(code, { code = it.filter(Char::isDigit).take(8) }, t("f_code"), Icons.Rounded.Pin, keyboard = KeyboardType.NumberPassword, ime = ImeAction.Go, onDone = ::submit)
        }
        if (error != null) ErrorText(errText(reason))
        SubmitButton(if (needCode) t("confirm") else t("sign_in"), busy, if (needCode) code.length >= 4 else login.isNotBlank() && password.isNotEmpty(), ::submit)
        if (needCode) TextButton(onClick = { needCode = false; code = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(t("back")) }
    }
}

@Composable
private fun MobileForm() {
    val t = LocalTr.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("+998 ") }
    var code by remember { mutableStateOf("") }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        busy = true
        failed = false
        scope.launch {
            val step = runCatching { if (sentTo == null) Repo.mobileSend(phone) else Repo.mobileConfirm(code) }.getOrElse { LoginStep.Error(null) }
            busy = false
            when (step) {
                is LoginStep.CodeSent -> sentTo = step.phone
                is LoginStep.Error -> { reason = step.reason; failed = true }
                else -> {}
            }
        }
    }

    Column {
        if (sentTo == null) {
            Field(phone, { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(17) }, t("f_phone"), Icons.Rounded.Phone, keyboard = KeyboardType.Phone, ime = ImeAction.Go, onDone = ::submit)
        } else {
            Text(t("code_sent", "phone" to sentTo), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            Field(code, { code = it.filter(Char::isDigit).take(8) }, t("f_code"), Icons.Rounded.Pin, keyboard = KeyboardType.NumberPassword, ime = ImeAction.Go, onDone = ::submit)
        }
        if (failed) ErrorText(oneIdError(reason))
        SubmitButton(if (sentTo == null) t("get_code") else t("confirm"), busy, if (sentTo == null) phone.count(Char::isDigit) >= 9 else code.length >= 4, ::submit)
        if (sentTo != null) TextButton(onClick = { sentTo = null; code = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(t("back")) }
    }
}

private fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val pixels = IntArray(m.width * m.height)
    for (y in 0 until m.height) for (x in 0 until m.width) pixels[y * m.width + x] = if (m.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
}

/**
 * Вход по QR-коду OneID. Код живёт 30 секунд: раз в 3 секунды спрашиваем, отсканирован ли он,
 * и сами меняем истёкший. На этом же телефоне код удобно сохранить в галерею и выбрать в сканере OneID.
 */
@Composable
private fun QrForm() {
    val t = LocalTr.current
    val context = LocalContext.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var expires by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        bmp = null
        try {
            val qr = Repo.qrStart()
            bmp = qrBitmap(qr.payload())
            expires = qr.expiresAt()
            var lastCheck = 0L
            while (isActive) {
                delay(500)
                now = System.currentTimeMillis()
                if (now - lastCheck < 3000) continue
                lastCheck = now
                when (Repo.qrCheck()) {
                    LmsService.QrStatus.OK -> {
                        if (!Repo.qrFinish()) error = t("err_link")
                        return@LaunchedEffect
                    }
                    LmsService.QrStatus.UNREACHABLE -> { error = t("err_unreachable"); return@LaunchedEffect }
                    LmsService.QrStatus.ERROR -> { error = t("err_oneid"); return@LaunchedEffect }
                    else -> if (expires - now < 4000) {
                        val next = Repo.qrNext()
                        bmp = qrBitmap(next.payload())
                        expires = next.expiresAt()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (e is java.io.IOException) t("err_unreachable") else t("err_oneid")
        }
    }
    DisposableEffect(Unit) { onDispose { Repo.qrCancel() } }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(t("qr_hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
        Surface(shape = RoundedCornerShape(28.dp), color = Color.White, shadowElevation = 2.dp, modifier = Modifier.size(260.dp)) {
            Box(contentAlignment = Alignment.Center) {
                val b = bmp
                if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize().padding(14.dp).clip(RoundedCornerShape(12.dp)))
                else if (error == null) CircularProgressIndicator()
            }
        }
        Spacer(Modifier.height(14.dp))
        if (bmp != null && error == null) {
            val left = ((expires - now) / 1000).coerceAtLeast(0)
            LinearProgressIndicator(
                progress = { (left / 30f).coerceIn(0f, 1f) },
                modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().clip(RoundedCornerShape(50)),
            )
            Spacer(Modifier.height(8.dp))
            Text(t("qr_valid", "s" to left), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t("qr_waiting"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = {
                val b = bmp ?: return@FilledTonalButton
                scope.launch {
                    val ok = runCatching { saveImage(context, b) }.getOrDefault(false)
                    snack.show(if (ok) t("qr_saved") else t("download_failed"), error = !ok)
                }
            }) {
                Icon(Icons.Rounded.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("qr_save"))
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            ErrorText(error)
            FilledTonalButton(onClick = { attempt++ }) {
                Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("retry"))
            }
        }
    }
}

private suspend fun saveImage(context: android.content.Context, bmp: Bitmap): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val name = "oneid_qr_${System.currentTimeMillis()}.png"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TUIT LMS")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@withContext false
        context.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } else {
        @Suppress("DEPRECATION")
        val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "TUIT LMS").apply { mkdirs() }
        java.io.File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }
}
