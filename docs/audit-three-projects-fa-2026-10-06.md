# گزارش ممیزی تطبیقی سه پروژهٔ VPN

**تاریخ بررسی:** 2026-10-06<br>
**پروژه‌ها:** `mlmvpn/mlmvpn_android`، `CluvexStudio/ZedSecure` و وب‌سایت `proton-generation.github.io`<br>
**هدف:** مقایسهٔ معماری و چرخهٔ اتصال، امنیت/حریم خصوصی، تجربهٔ کاربری، آزمون و انتشار، وابستگی‌ها و مجوزها؛ همراه با الگوهای معماریِ قابل‌استفاده و ریسک‌های مرتبط با پروژهٔ فعلی.

> این گزارش ممیزی ایستا است. هیچ build یا test اجرا نشده، APK منتشرشده دانلود یا تحلیل ایستا نشده، و هیچ VPN یا endpoint عملیاتی‌ای برای probe استفاده نشده است. «مشاهدهٔ کد» یعنی رفتار در snapshot مشخص‌شده دیده می‌شود؛ «ادعای مستندات» به README/سند پروژه نسبت داده می‌شود؛ «نامعلوم» یعنی از منبع عمومی بررسی‌شده نتیجه‌گیری قابل اتکا ممکن نیست. شدت‌ها اولویت بازبینی‌اند، نه نتیجهٔ pentest یا اثبات سوءاستفاده.

## ۱) snapshot و دامنه

| پروژه | snapshot منبع بررسی‌شده | انتشار/نسخهٔ اعلامی | مجوز و نوع پروژه |
|---|---|---|---|
| [MLM VPN Android](https://github.com/mlmvpn/mlmvpn_android) | `main` و tag بررسی‌شده: `22b97b24a1d68853cac1f6ecbbf611a5101bec26`، تاریخ commit برابر 2026-10-01 | GitHub [آخرین release را `v1.2.40`](https://github.com/mlmvpn/mlmvpn_android/releases/tag/v1.2.40) با تاریخ 2026-10-05 نشان می‌دهد؛ اما `app/build.gradle` در همان commit `versionName "1.2.39"` دارد و tag `v1.2.40` نیز به همین SHA می‌رسد. علت/مسیر ساخت APK از کد عمومی روشن نیست. | `GPL-3.0`; اپ Android چندموتوره و مجموعه‌ای از ابزارهای شبکه/کلاد |
| [ZedSecure](https://github.com/CluvexStudio/ZedSecure) | `main`: `7c9639933abe7033c9f44a4a52f13ec1e74f8b55`، تاریخ 2026-10-03 | `3.1.4`، آخرین release مشاهده‌شده | `AGPL-3.0`; اپ Android و desktop با کد Kotlin Multiplatform مشترک |
| [Proton Generation Pages](https://github.com/proton-generation/proton-generation.github.io) | `main`: `da9297ab024d8cbce6121435ef659442b549529a`، تاریخ 2026-09-19 | سایت GitHub Pages؛ release اپلیکیشنی مشاهده نشد | در metadata GitHub مجوز ثبت نشده و در tree بررسی‌شده فایل LICENSE هم نبود؛ فقط وب‌سایت است، نه کلاینت VPN |

**مرزبندی مهم:** Proton فقط به‌عنوان سایت وب بررسی شده است. این ممیزی هیچ مجوزی برای استفاده، بازتولید، فراخوانی یا ادغام generator نمی‌دهد؛ مطابق تصمیم قبلی، generator کنار گذاشته می‌ماند. همچنین از MLM و Zed فقط می‌توان ایدهٔ معماری گرفت، نه کد. هیچ‌یک از منابع عمومی این ممیزی جایگزین منبع رسمی یا کانفیگ ارائه‌شده توسط کاربر برای اپ خودمان نیست.

## ۲) خلاصهٔ اجرایی

- **بهترین ایدهٔ معماری برای چرخهٔ اتصال:** در Zed، جداسازی برنامه‌ریزی اتصال از اجرای engine، مراحل روشن start/fail، و سنجش readiness پس از بالا آمدن core ارزش بررسی دارد. بااین‌حال، مسیر DNS پیش از تونل و چند مورد ذخیره/ارسال رازها، الگوهایی نیستند که باید منتقل شوند.
- **بهترین ایدهٔ مدیریت state:** در MLM، queueهای محدود و state per-network/per-service و نوشتن نتایج با شناسهٔ پایدار الگوهای قابل مطالعه‌اند؛ در مقابل، رفتارهای خودکار آن با سیاست فعلی ما—آزمون فقط پس از اقدام کاربر و recommendation/sorting غیرفعال از نظر probe—هم‌خوان نیستند.
- **ریسک داده‌ای مهم در MLM:** `NodeManager`، URI کامل هر node را در SharedPreferences معمولی می‌نویسد. هم‌زمان backup روشن است و فایل preference مربوط به nodeها در قواعد backup صریحاً مستثنا نشده؛ بنابراین credentialهای واردشده ممکن است در Android backup/device transfer قرار گیرند.
- **ریسک‌های مهم در Zed:** ابزار `get_settings` می‌تواند `socksPassword` را به provider انتخاب‌شدهٔ AI برساند؛ log خام هم redaction آشکاری ندارد. کد رمزگذاری بعضی secretهای محلی از کلید ثابتِ داخل برنامه استفاده می‌کند، و `StartPlanner` در مسیر انتخابی می‌تواند hostnameها را قبل از تونل با DNS سیستم resolve کند.
- **ریسک اصلی وب‌سایت Proton:** کلید خصوصی WireGuard در `localStorage` می‌ماند، در حالی که صفحه چند اسکریپت بیرونی بدون SRI بار می‌کند. این مسیر، در صورت compromise/XSS در زنجیرهٔ script، کلید را در معرض خواندن قرار می‌دهد. از کد client دیده‌شده مدرکی برای ارسال WG private key به API پیدا نشد؛ backend خارج از repo و ممیزی‌نشده است.
- **برای پروژهٔ خودمان:** مسیر فعلی Keystore/AES-GCM در `SecureProfileStore`، تفکیک Quick/Real/Verified و آزمون‌های دستیِ محدود و کاربرآغازشده با محدودیت‌های ثبت‌شده سازگارترند. پالت سفید/مشکی با accent محدود، آیکون VPN و نمایش traffic نیز باید حفظ شوند؛ این گزارش تغییر ظاهری یا تغییر کد پیشنهاد نمی‌کند.

## ۳) جزئیات MLM VPN Android

### معماری و lifecycle

- **[کد]** اپ یک کلاینت VPN ساده نیست؛ Android/Compose میزبان چند engine، Cloudflare integration، scannerها، پنل‌ها و ابزارهای تشخیصی است. `MyVpnService` و کلاس‌های engine مدیریت اتصال را میان Xray و چند native/third-party engine هماهنگ می‌کنند. ساخت release به تعداد زیادی `.so` و AAR از پیش‌ساخته وابسته است.
- **[کد + سند]** در `FLUX` کنترل انتخاب مسیر از data plane Xray جداست. کد cache فهرست‌ها را نگه می‌دارد، منبع‌های HTTPS را در زمان لازم به‌روزرسانی می‌کند و می‌تواند پس از اتصال سلامت مسیر را بررسی کند. مستند معماری می‌گوید «connected» فقط پس از موفقیت یک درخواست واقعی از تونل اعلام می‌شود؛ این اصل از نظر semantics از صرفاً روشن شدن engine بهتر است.
- **[کد + سند]** `MAE` کنترل‌پلین per-app/per-network دارد: انتخاب route، صف‌بندی بررسی سرویس‌ها، canary داخل تونل و خودترمیم. بخشی از بررسی‌ها بر اساس وضعیت stale، تغییر شبکه، incident یا سلامت مسیر خودکار صف می‌شوند؛ این ویژگی با الزام ما به شروع تست فقط با اقدام کاربر تعارض دارد.
- **[کد]** در `VpnGateTab`، اولین بازدید بدون سرور انتخاب‌شده، تا ۱۲ گزینهٔ برتر را ping می‌کند و سریع‌ترین پاسخ‌دهنده را انتخاب می‌کند. این اسکن، queue-wide نیست، اما همچنان probe بدون درخواست مستقیم کاربر است. آن را برای پروژهٔ خودمان الگو نگیریم.
- **[کد]** در `FLUX` نیز به‌روزرسانی sourceها و health check متصل به session وجود دارد. URLهای user subscription پذیرفته می‌شوند، اما فهرست‌های built-in/public هم وجود دارند؛ این sourceها با سیاست «فقط official یا user-provided» ما قابل انتقال نیستند.

**الگوی قابل‌مطالعه:** حفظ state per-network، اولویت‌دهی به خطای واقعی کاربر، وضعیت‌های قابل لغو و بررسی واقعی مسیر پس از اتصال. **محدودیت انتقال:** فقط به‌صورت ایدهٔ طراحی، نه کد یا source-list؛ هر probe در محصول ما باید با action صریح کاربر آغاز شود.

### امنیت و حریم خصوصی

1. **ریسک بالا — ذخیرهٔ plaintext و احتمال backup:**
   - **[کد]** `NodeManager` preference با نام `vpn_nodes_prefs` می‌خواند و `node.uri` را در JSON کلید `uri` می‌نویسد. URIهای VLESS/VMess/WireGuard و مشابه می‌توانند credential یا private key داشته باشند.
   - **[کد]** `AndroidManifest.xml` دارای `android:allowBackup="true"` است. فایل‌های `backup_rules.xml` و `data_extraction_rules.xml`، `cloud_accounts_prefs.xml`، پوشه‌های `mae/` و `flux/` و چند مورد دیگر را exclude می‌کنند، اما `vpn_nodes_prefs.xml` را نه.
   - **[تحلیل، نیازمند runtime validation]** با فعال بودن backup/device transfer در محیط کاربر، preferenceهای node ممکن است وارد نسخهٔ پشتیبان شوند. این گزارش backup واقعی دستگاه را اجرا نکرده؛ پس «ممکن است» دقیق‌تر از ادعای انتقال قطعی است.
   - شاهد: [`NodeManager.kt` (SharedPreferences و JSON `uri`)](https://github.com/mlmvpn/mlmvpn_android/blob/22b97b24a1d68853cac1f6ecbbf611a5101bec26/app/src/main/java/com/mlmvpn/scanner/data/NodeManager.kt#L34)، [`AndroidManifest.xml`](https://github.com/mlmvpn/mlmvpn_android/blob/22b97b24a1d68853cac1f6ecbbf611a5101bec26/app/src/main/AndroidManifest.xml#L46)، [`backup_rules.xml`](https://github.com/mlmvpn/mlmvpn_android/blob/22b97b24a1d68853cac1f6ecbbf611a5101bec26/app/src/main/res/xml/backup_rules.xml) و [`data_extraction_rules.xml`](https://github.com/mlmvpn/mlmvpn_android/blob/22b97b24a1d68853cac1f6ecbbf611a5101bec26/app/src/main/res/xml/data_extraction_rules.xml).

2. **کنترل مثبت، اما محدود به بخشی از داده‌ها:**
   - **[کد]** `SecureStore` از AES-256-GCM و Android Keystore برای فهرست حساب‌های Cloudflare استفاده می‌کند؛ CloudManager نسخهٔ قدیمی plaintext را migrate می‌کند و پس از seal موفق آن را حذف می‌کند.
   - **[کد]** اگر Keystore در دستگاه خراب/غیردسترس باشد، CloudManager عمداً به plaintext fallback می‌کند تا حساب‌ها را از دست ندهد. این تصمیم در سورس مستند شده، ولی تضمین محرمانگی دیگر برقرار نیست.
   - **[کد]** این حفاظت، URIهای `NodeManager` را شامل نمی‌شود؛ نباید آن را به معنی رمزگذاری همهٔ پروفایل‌ها دانست.
   - شاهد: [`SecureStore.kt`](https://github.com/mlmvpn/mlmvpn_android/blob/22b97b24a1d68853cac1f6ecbbf611a5101bec26/app/src/main/java/com/mlmvpn/scanner/data/SecureStore.kt#L13) و [`CloudManager.kt`](https://github.com/mlmvpn/mlmvpn_android/blob/22b97b24a1d68853cac1f6ecbbf611a5101bec26/app/src/main/java/com/mlmvpn/scanner/data/CloudManager.kt#L77).

3. **سطح مجازسازی شبکه و permissionها:**
   - **[کد]** manifest، علاوه بر INTERNET و سرویس VPN، `usesCleartextTraffic="true"` را سراسری اعلام می‌کند. این یعنی Android در سطح policy درخواست‌های cleartext اپ را مسدود نمی‌کند؛ به‌تنهایی ثابت نمی‌کند هر درخواست واقعاً HTTP است.
   - **[کد]** permissionهای پرقدرت‌تری مانند `QUERY_ALL_PACKAGES`، `REQUEST_INSTALL_PACKAGES`، overlay و location/Wi-Fi نیز وجود دارند. بعضی با قابلیت‌های گستردهٔ برنامه توجیه می‌شوند، اما برای build کوچک‌تر یا توزیع محدود باید نیاز هرکدام و افشای UX آن جداگانه بازبینی شود.

4. **Crash telemetry:**
   - **[کد]** uncaught crash ثبت می‌شود؛ در نبود opt-out، upload job با نیاز به network و retry پایدار زمان‌بندی می‌شود. breadcrumbها، stack/error و خلاصهٔ دستگاه/نسخه در مسیر report هستند.
   - **[مستندات]** `docs/CRASH-REPORTS.md` مقصد را collectorهای Cloudflare و در نهایت issue در مخزن خصوصی `mlmvpn/crashes` توصیف می‌کند. همان سند می‌گوید کاربر در اولین ارسال خودکار مطلع می‌شود و می‌تواند آن را خاموش کند.
   - **[کد]** redactor الگوهای Bearer/token/password/email و چند شکل شناخته‌شده را حذف می‌کند، اما خود کامنت صریح می‌گوید shapeهای ناشناخته عبور می‌کنند. بنابراین ادعای مستندات مبنی بر نبود config/traffic را باید «ادعای پروژه» دانست؛ بررسی کامل همهٔ متن‌های exception/native tombstone و server-side retention در این ممیزی انجام نشد.
   - شاهد: [`CrashReporter.kt`](https://github.com/mlmvpn/mlmvpn_android/blob/22b97b24a1d68853cac1f6ecbbf611a5101bec26/app/src/main/java/com/mlmvpn/scanner/CrashReporter.kt#L97) و [`SecretRedactor.kt`](https://github.com/mlmvpn/mlmvpn_android/blob/22b97b24a1d68853cac1f6ecbbf611a5101bec26/app/src/main/java/com/mlmvpn/scanner/utils/SecretRedactor.kt#L3).

### UX

- **[کد/مستندات]** پوشش قابلیت‌ها زیاد است: tabهای متعدد، اسکن، پنل‌های Cloud، engineها و ابزارهای جداگانه. مزیت آن دسترسی به کارهای تخصصی است؛ هزینه‌اش پیچیدگی ذهنی و افزایش سطح مجوز، state و مسیرهای نگهداری است.
- **[کد]** progressive status، اندازه‌گیری مرحله‌ای و resultهای قابل مشاهده برای بعضی scannerها تجربهٔ سریع‌تری از نمایش spinner تا پایان کار می‌سازند. اما auto-pick مبتنی بر ping در بازدید اول ممکن است برای کاربری که انتظار «فقط پس از لمس تست» دارد غیرمنتظره باشد.
- **برای پروژهٔ خودمان:** از breadth یا navigation این محصول تقلید نشود؛ ایدهٔ مفید صرفاً feedback مرحله‌ای است و باید فقط در عملیات user-triggered ما اعمال شود.

### آزمون، انتشار، وابستگی و مجوز

- **[کد]** snapshot شامل **۵۵ فایل Kotlin** زیر `app/src/test` و صفر مسیر `app/src/androidTest` است؛ تعداد فایل‌ها تعداد test case نیست. در tree بررسی‌شده workflow قابل مشاهده‌ای زیر `.github/workflows` نبود؛ پس CI انتشار از همین سورس عمومی اثبات نمی‌شود. Gradle taskهای سفارشی نیز وجود دارند.
- **[کد]** `compileSdk/targetSdk 34`، `minSdk 24`، AGP `8.3.2` و Kotlin `1.9.22` دیده می‌شوند. dependencies ترکیبی از AndroidX/Compose، Coroutines، OkHttp، Google Play Services Auth، ZXing، AmneziaWG و AAR/native coreهای prebuilt است؛ dependencyهای AAR با `fileTree` وارد می‌شوند و به‌سادگی از همان فهرست Gradle نسخه‌به‌نسخه قابل ممیزی نیستند.
- **[کد]** `jniLibs` شامل کتابخانه‌های native از چند ABI و در build comments از یازده core/library به‌ازای هر ABI نام برده شده است؛ توضیح build از APKهای بسیار حجیم حکایت دارد. وجود source یا README برای بعضی coreها به‌تنهایی reproducibility یا برابری آن با باینری release را اثبات نمی‌کند.
- **[مشاهدهٔ metadata + کد]** release اخیر `v1.2.40` است، اما سورس tag/build قابل مشاهده `1.2.39` می‌گوید و tag همان commit `22b97b2` است. این اختلاف provenance/version را مبهم می‌کند؛ برای نسبت‌دادن باینری به سورس، به build/signature/SHA قابل بازتولید نیاز است.
- **[مجوز]** پروژه GPL-3.0 است؛ native engines و AARهای همراه ممکن است مجوزهای متفاوت و noticeهای جدا داشته باشند. پیش از هر استفادهٔ مشتق‌شده بررسی سازگاری مجوز لازم است.

## ۴) جزئیات ZedSecure

### معماری و lifecycle

- **[کد]** منطق مشترک در `shared` با Kotlin Multiplatform قرار دارد و Android و desktop adapter/entry point خود را دارند. روی Android، `ZedVpnService` موتور را start/stop می‌کند، وضعیت انتقال را منتشر می‌کند، loop ترافیک را به‌روز می‌کند و تغییر شبکه را مدیریت می‌کند.
- **[کد]** `StartPlanner` config runtime را می‌سازد و سرویس پس از بالا آمدن engine می‌تواند `TunnelReadiness.verify()` را صدا بزند. این verifier از loopback SOCKS5 (`127.0.0.1`) به `cp.cloudflare.com:80` درخواست `HEAD` می‌فرستد و وجود پاسخ را شرط موفقیت می‌گیرد؛ اتصال local proxy و یک پاسخ شبکه را بررسی می‌کند، اما status code یا موفقیت یک برنامهٔ کاربر را تحلیل نمی‌کند.
- **[کد]** شکست اتصال با kill switch فعال می‌تواند TUN نگه‌دارنده بسازد؛ در غیر این صورت سرویس همه‌چیز را متوقف می‌کند. مسیر `SNI spoof` نیز پس از core start و TUN bridge همین readiness probe را انجام می‌دهد. مسیرهای engine متفاوت‌اند؛ این verifier را نباید برای همهٔ پروتکل‌ها تعمیم داد.
- **[کد]** import فایل `.zsx` ابتدا فایل را می‌خواند و metadata/expiry را بررسی می‌کند و سپس برای config قفل‌شده مسیر ورود رمز دارد. README از import لینک، subscription، JSON/OVPN/QR و share کردن vault نام می‌برد.
- **[مستندات]** README می‌گوید Auto-select tunnel را به سرور سریع‌تر می‌برد و failover می‌کند. بخشی از ping/monitoring این قابلیت پس از فعال‌سازی session خودکار است؛ این رفتار را نباید به پروژهٔ ما منتقل کرد. «تست همه» در UI هم با اقدام کاربر شروع می‌شود، ولی auto-select مسیر جداگانه‌ای دارد.

**الگوی قابل‌مطالعه:** start phaseهای مشخص، verify جدا از شروع engine، خطای قابل‌فهم، رفتار kill-switch در شکست و import مرحله‌ای. در پروژهٔ ما این‌ها فقط معیار مقایسه‌اند؛ نگاشت Xray import/start/verify خودمان، آیکون VPN، traffic و UX فعلی حفظ می‌شود.

### امنیت و حریم خصوصی

1. **ریسک بالا — secret در خروجی AI `get_settings`:**
   - **[کد]** `AndroidAiBridge` خود `SettingsRepository.settings.value` را به `AppAiBridge` می‌دهد. `settingsJson()` کل `AppSettings` را serialize می‌کند و فقط `ai` را از JSON حذف می‌کند؛ `socksPassword` یک فیلد مستقل در `AppSettings` است و در `SettingsRepository` هم به preference با کلید `socks_password` نوشته می‌شود.
   - بنابراین اگر agent ابزار `get_settings` را فراخوانی کند، رمز SOCKS می‌تواند در tool-result قرار گیرد و به AI provider انتخابی فرستاده شود. این یافته به فراخوانی ابزار وابسته است، نه اینکه هر بار اپ بدون AI آن را ارسال کند.
   - **[کد]** `logsText()` لاگ خام `LogBus` را برمی‌گرداند و redaction در همان تابع دیده نمی‌شود. در نتیجه secretهایی که وارد log شده‌اند نیز ممکن است با `read_logs` به provider بروند.
   - شاهد: [`AppAiBridge.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/shared/src/commonMain/kotlin/dev/cluvex/zedsecure/domain/ai/AppAiBridge.kt#L40)، [`AndroidAiBridge.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/app/src/main/java/dev/cluvex/zedsecure/ai/AndroidAiBridge.kt#L24) و [`SettingsRepository.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/app/src/main/java/dev/cluvex/zedsecure/data/settings/SettingsRepository.kt#L307).

2. **ریسک بالا — اجرای tool مخرب متکی به prompt:**
   - **[کد]** `allowChanges` در مدل AI به‌طور پیش‌فرض `true` است. `AiTool` فیلد `destructive` دارد و prompt از مدل می‌خواهد پیش از بعضی کارها تأیید بگیرد؛ اما `AiAgent` پاسخ tool-call را مستقیماً به `AiTools.run()` می‌دهد و در مسیر اجرای tool، guard اپ‌محور برای `destructive` دیده نمی‌شود.
   - وقتی کاربر قابلیت تغییر را روشن کرده باشد، `disconnect` یا حذف دائمی server به policy متنی prompt متکی می‌شود، نه دیالوگ تأیید اجباری در لایهٔ اپ. این نقص کنترل/UX است، نه اثبات اینکه مدل حتماً چنین کاری انجام می‌دهد.
   - شاهد: [`AiAgent.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/shared/src/commonMain/kotlin/dev/cluvex/zedsecure/domain/ai/AiAgent.kt#L54) و [`AiTools.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/shared/src/commonMain/kotlin/dev/cluvex/zedsecure/domain/ai/AiTools.kt#L141).

3. **ریسک بالا — «sealed» بودن بعضی secretها، رمزگذاری device-bound نیست:**
   - **[کد]** ذخیرهٔ AI key و IKEv2 password/PSK از `ZsxCrypto.seal()` بدون password استفاده می‌کند. در این حالت `ZsxCrypto` mode 0 را به‌کار می‌برد و کلید AES را از عبارت ثابت `"ZedSecure .zsx v2"` با SHA-256 می‌سازد. بنابراین پیشوند `zsx:` و AES-GCM در این مسیر، رمزگذاریِ device-bound نیست؛ کسی که به preference خصوصی و APK/source دسترسی داشته باشد می‌تواند کلید را مشتق کند. این با `.zsx` دارای password فرق دارد که مسیر KDF/password و AES-GCM جداگانه دارد.
   - **[کد]** `SettingsRepository` مقدار `socks_password` را هم به‌صورت رشته در preference می‌نویسد؛ در manifest مقدار `allowBackup=false` است که سطح خطر backup را کم می‌کند، اما plaintext بودن در storage اپ را عوض نمی‌کند.
   - شاهد: [`AiTypes.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/shared/src/commonMain/kotlin/dev/cluvex/zedsecure/domain/ai/AiTypes.kt#L63)، [`Ikev2Profile.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/shared/src/commonMain/kotlin/dev/cluvex/zedsecure/domain/config/Ikev2Profile.kt#L55)، [`ZsxCrypto.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/shared/src/jvmMain/kotlin/dev/cluvex/zedsecure/crypto/ZsxCrypto.kt#L25) و [`AndroidManifest.xml`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/app/src/main/AndroidManifest.xml#L26).

4. **ریسک بالا — احتمال query DNS پیش از تونل:**
   - **[کد]** مقدار پیش‌فرض `outboundDomainResolve` برابر `ResolveAndAddToHosts` است. وقتی planner برای ساخت config resolve لازم بداند و کاربر `DoNotResolve` را انتخاب نکرده باشد، `StartPlanner.resolveServerHosts()` از `InetAddress.getAllByName()` روی hostname سرورهای فیزیکی و بعضی outboundهای rules استفاده می‌کند.
   - **[تحلیل]** این فراخوانی می‌تواند DNS را از resolver شبکهٔ میزبان و پیش از برقراری VPN بفرستد؛ بنابراین مسیر بالقوهٔ DNS leak است، نه مشاهدهٔ یک packet leak در runtime. مسیر SNI spoof نیز fallback به `InetAddress.getByName(realHost)` دارد.
   - شاهد: [`StartPlanner.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/app/src/main/java/dev/cluvex/zedsecure/core/StartPlanner.kt#L249) و [`ZedVpnService.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/app/src/main/java/dev/cluvex/zedsecure/core/ZedVpnService.kt#L307).

5. **داده‌ای که خود ابزار AI به provider می‌دهد:**
   - **[کد]** `exit_info` IP خروجی و در صورت دسترس کشور، شهر و ISP را به‌عنوان tool-result برمی‌گرداند. این داده با اجرای ابزار به agent می‌رسد و نتیجهٔ chat/tool برای provider ارسال می‌شود.
   - **[کد]** `ping` در صورت نداشتن ID، روی تمام سرورهای فهرست‌شده اجرا می‌شود؛ این رفتار یک قابلیت ابزار AI است و با درخواست کاربر برای probe دستی/محدود باید ناسازگار تلقی شود.
   - شاهد: [`AndroidAiBridge.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/app/src/main/java/dev/cluvex/zedsecure/ai/AndroidAiBridge.kt#L97) و [`AiTools.kt`](https://github.com/CluvexStudio/ZedSecure/blob/7c9639933abe7033c9f44a4a52f13ec1e74f8b55/shared/src/commonMain/kotlin/dev/cluvex/zedsecure/domain/ai/AiTools.kt#L183).

6. **کنترل مثبت:**
   - **[کد]** `android:allowBackup="false"` در manifest ثبت شده است. این محافظ به‌تنهایی مشکل leak تنظیمات به provider، plaintext محلی، DNS پیش از تونل یا رفتار tool را حل نمی‌کند.

### UX

- **[کد/README]** برنامه multi-platform و چندموتوره است؛ import از لینک/QR/فایل، وضعیت اتصال و چند ابزار پیکربندی را در یک اپ عرضه می‌کند. README از زبان‌های انگلیسی، فارسی، روسی و چینی و تم روشن/تیره نام می‌برد.
- **[کد]** آماده‌سازی `.zsx` با بررسی metadata/expiry پیش از unlock، و تفکیک صفحه/تنظیمات engine، الگوی مفیدی برای UX است. در مقابل، فهرست بلند پروتکل‌ها و گزینه‌های routing/DNS/core می‌تواند پیچیدگی محصول را زیاد کند.
- **برای پروژهٔ خودمان:** می‌توان از وضوح مراحل، خطای اختصاصی engine و status قابل مشاهده ایده گرفت؛ نه از palette، کد UI یا رفتارهای پرگزینه. تم سفید/مشکی و accent محدودِ اپ خودمان ثابت می‌ماند.

### آزمون، انتشار، وابستگی و مجوز

- **[کد]** tree این snapshot دارای **۸۸ فایل Kotlin** در `app/src/test`، صفر فایل/مسیر `app/src/androidTest` و ۲۰ فایل Kotlin در `desktop/src/test` است. این اعداد تعداد فایل‌اند، نه test case.
- **[کد]** ۶ workflow در GitHub Actions وجود دارد. `android-release.yml`، native coreها را تهیه/می‌سازد، `:app:testDebugUnitTest` و `:app:assembleRelease` را اجرا و امضا/ABI/checksum را بررسی می‌کند؛ workflowهای desktop هم `:desktop:test` و بسته‌بندی پلتفرم را دارند. در این ممیزی workflow اجرا نشد.
- **[کد]** نکتهٔ مثبت supply-chain: `tools/core-sources.txt` برای چند fork/core SHA دقیق ثبت می‌کند و `NOTICE` مجوزهای همراه را لیست می‌کند. نکتهٔ باقیمانده: بخشی از toolchain در workflow با نسخهٔ `@latest` نصب می‌شود و همهٔ actionها به commit SHA ثابت pin نشده‌اند؛ پس از نظر reproducibility همهٔ زنجیره یکساناً قفل نیست.
- **[کد]** dependencyها در version catalog متمرکزند: Kotlin/Compose/Coroutines، AndroidX، BouncyCastle، JSch، ZXing و native engines. هسته‌های Go/native از source revisionهای جداگانه build یا به‌صورت release artifact تهیه می‌شوند.
- **[مجوز]** اپ AGPL-3.0 است؛ README و `NOTICE` مجوزهای GPL/LGPL اجزای مختلف را هم توضیح می‌دهند. استفاده از کد آن در پروژهٔ دیگر نیازمند بررسی دقیق تعهدات copyleft است و با تصمیم «کد کپی نشود» ما منطبق است.

## ۵) جزئیات وب‌سایت Proton Generation

### معماری و UX

- **[کد]** این repo یک سایت استاتیک client-side است، نه اپ Android و نه source یک VPN backend کامل. صفحه یک جریان چندمرحله‌ای دارد: ایجاد/دریافت session، انتخاب endpoint، ساخت متن کانفیگ و نمایش/دانلود خروجی. `JSZip` برای بسته‌بندی و `script.js`/`quic.js` برای رفتار مرورگر بارگیری می‌شوند.
- **[کد]** متن‌های UI مشاهده‌شده عمدتاً روسی‌اند؛ در repo ۹ فایل وجود دارد و `package.json`، lockfile، test directory و `.github/workflows` دیده نشد. UX یک ابزار وب تک‌منظوره است؛ قابل قیاس مستقیم با چرخهٔ Android VPN نیست.
- **[نامعلوم]** جزئیات deploy/headers واقعی GitHub Pages و سیاست‌های مرورگر در production را فقط از source tree نمی‌توان کامل اثبات کرد.

### امنیت و حریم خصوصی

1. **ریسک بالا، مشروط به compromise زنجیرهٔ script — کلید در localStorage:**
   - **[کد]** `wgPrivateKey` و `protonCertData` در `localStorage` قرار می‌گیرند. کلید با TweetNaCl در browser ساخته می‌شود و برای ساخت کانفیگ، private key در خروجی `.conf`/YAML قرار می‌گیرد. پاک‌سازی session این کلیدها را حذف می‌کند، اما تا آن زمان ذخیرهٔ پایدار مرورگرند.
   - **[کد]** `index.html`، Tailwind CDN، TweetNaCl، TweetNaCl-util، JSZip و polyfill پرچم را از originهای بیرونی بار می‌کند؛ برای این scriptهای بررسی‌شده `integrity`/SRI وجود ندارد. اسکریپت ثالثی که در context همان صفحه اجرا شود می‌تواند به localStorage دسترسی داشته باشد.
   - **[تحلیل]** XSS یا compromise زنجیرهٔ CDN می‌تواند به کلید خصوصی ذخیره‌شده دسترسی پیدا کند؛ این ریسک شرطی است و در ممیزی حاضر رخداد XSS یا compromise مشاهده نشده است.
   - شاهد: [`index.html`](https://github.com/proton-generation/proton-generation.github.io/blob/da9297ab024d8cbce6121435ef659442b549529a/index.html#L7) و [`script.js`](https://github.com/proton-generation/proton-generation.github.io/blob/da9297ab024d8cbce6121435ef659442b549529a/script.js#L257).

2. **ارسال به backend و محدودیت شواهد:**
   - **[کد]** درخواست `/api/proton/certificate` که از client بررسی شد، `session`، `clientPublicKey` و `persistent: true` می‌فرستد؛ payload همان درخواست WG private key ندارد. بنابراین در این مسیر از سورس client مدرکی ندیدم که private key به API ارسال شود.
   - **[نامعلوم]** درخواست‌ها به `https://proton-api.vercel.app` می‌روند، اما backend در repo وب‌سایت نیست. پیاده‌سازی session/certificate، logging، retention، کنترل دسترسی، محدودیت نرخ و رفتارهای دیگر قابل ممیزی نبودند. این گزارش هیچ درخواست واقعی به API نفرستاد.
   - شاهد: [`script.js`](https://github.com/proton-generation/proton-generation.github.io/blob/da9297ab024d8cbce6121435ef659442b549529a/script.js#L66) و ساخت payload در [همان فایل](https://github.com/proton-generation/proton-generation.github.io/blob/da9297ab024d8cbce6121435ef659442b549529a/script.js#L280).

3. **ریسک اعتبارسنجی خروجی:**
   - **[کد]** داده‌هایی مانند `server.name`، `server.entryIp` و `server.publicKey` در template خروجی YAML/متن config قرار می‌گیرند. بعضی فیلدها پاک‌سازی نام دارند، اما escape/validation همهٔ مقدارهای server را از این محل نمی‌توان نتیجه گرفت.
   - **[تحلیل/نامعلوم]** اگر پاسخ backend قابل کنترل یا آلوده باشد، خروجی نامعتبر یا دستکاری‌شده ممکن است ساخته شود. exploit مشخصی اثبات نشده و اعتمادپذیری پاسخ backend نیز در repo قابل ارزیابی نیست.

### آزمون، وابستگی و مجوز

- **[کد]** در tree بررسی‌شده test suite، lockfile و workflow آزمایش/انتشار دیده نشد؛ این به معنی نبود امکان deploy خودکار توسط GitHub Pages نیست، فقط در repo workflow قابل ممیزی وجود ندارد.
- **[کد]** وابستگی‌ها در زمان اجرا از CDN می‌آیند، نه از lockfile محلی. TweetNaCl/JSZip بعضاً نسخه در URL دارند، اما Tailwind و import مربوط به polyfill به نسخهٔ دقیق pin نشده‌اند.
- **[metadata]** مجوز ثبت‌شده‌ای برای مخزن پیدا نشد. نبود مجوز، اجازهٔ کپی یا ادغام ایجاد نمی‌کند؛ سایت فقط برای مشاهده و ممیزی بررسی شده است، نه استفاده یا فراخوانی آن در اپ.

## ۶) مقایسهٔ مستقیم

| محور | MLM VPN Android | ZedSecure | Proton Generation Pages |
|---|---|---|---|
| ماهیت | اپ Android بزرگ، چند موتور و ابزار کلاد/اسکن | اپ Android + desktop با لایهٔ Kotlin مشترک | سایت browser-side، نه کلاینت VPN |
| lifecycle | `MyVpnService` و engineهای متعدد؛ برخی انتخاب‌ها/بررسی‌ها خودکار | planner → engine/service → readiness و failure handling؛ بعضی auto-selectها خودکار | session وب و ساخت فایل؛ VPN/TUN lifecycle ندارد |
| probe خودکار | ping اولیهٔ ۱۲ گزینه در VpnGate؛ MAE/FLUX هم پایش پس از اتصال دارند | test-all دستی موجود است؛ Auto-select می‌تواند پایش/انتخاب خودکار انجام دهد | تولید فایل پس از تعامل کاربر؛ probe VPN در دامنهٔ سایت بررسی نشد |
| دادهٔ حساس | node URI در preference plaintext و backup احتمالی؛ Cloud token در Keystore فقط در مسیر موفق | نشت احتمالی SOCKS/log به AI، secrets با کلید ثابت، DNS پیش از تونل | private key در localStorage؛ چند CDN ثالث؛ backend بیرونی نامعلوم |
| UX قابل استفاده | نتیجه‌نمایی مرحله‌ای و state per-network | status و failure path صریح، import چندفرمتی | workflow وب ساده و مرحله‌ای |
| آزمون/CI | ۵۵ فایل Kotlin unit؛ بدون AndroidTest و workflow قابل مشاهده | ۸۸ فایل Android unit و ۲۰ desktop؛ ۶ workflow | suite و workflow در tree بررسی‌شده ندارد |
| مجوز | GPL-3.0 | AGPL-3.0 | مجوز ثبت نشده |

## ۷) نتیجه برای پروژهٔ خودمان

این ممیزی **تغییر کد پیشنهاد نمی‌کند**؛ تصمیم‌های زیر محدودهٔ سازگار برای ادامهٔ کار هستند:

1. **منبع کانفیگ:** فقط config رسمی/مجاز یا چیزی که کاربر خودش وارد کرده؛ public config pools و Proton generator خارج از scope باقی می‌مانند.
2. **probe و رتبه‌بندی:** تست Quick/Real/Verified، ping و آزمون queue فقط با لمس صریح کاربر شروع شوند، محدود و cancellable باشند. recommendation/sorting باید passive باشد و خودش probe تازه نسازد؛ هیچ queue-wide probe در startup، بازشدن صفحه، تغییر شبکه یا auto-connect فعال نشود.
3. **چرخهٔ اتصال:** تفکیک import/parse، ذخیرهٔ امن، درخواست مجوز Android، start سرویس، احراز موفقیت واقعی تونل و سپس نمایش وضعیت/traffic حفظ شود. «endpoint پاسخ داد» با «VPN end-to-end تأیید شد» یکی نیست.
4. **ذخیره و telemetry:** اصل `SecureProfileStore` فعلی—رمزگذاری raw config و subscription URL با Android Keystore/AES-GCM—از ذخیرهٔ plaintext node URI یا کلید ثابت بهتر است. backup/restore هم باید با کلید device-bound و policy صریح سنجیده شود؛ secrets و logهای خام به provider بیرونی ارسال نشوند.
5. **ظاهر:** از ساختار state و پیام خطا ایده بگیریم، نه از بازطراحی ظاهری. UI فعلی سفید/مشکی با accent محدود، آیکون VPN و نمایش traffic در scope باقی بماند.
6. **مجوز:** GPL/AGPL کد این دو اپ کپی نشود. سایت بدون LICENSE نیز مجوزی برای reuse نمی‌دهد. یادداشت مجوز dependencyهای خودمان، به‌ویژه مورد LGPL که در `THIRD_PARTY_NOTICES.md` ثبت شده، یک بررسی حقوقی جداگانه است.

## ۸) موارد نامعلوم و محدودیت‌های ممیزی

- backup واقعی دستگاه MLM و فعال بودن Cloud backup/device transfer به‌صورت runtime بررسی نشد.
- DNS قبل از تونل در Zed از فراخوانی resolver سیستم استنباط شده؛ packet capture یا آزمون شبکه انجام نشد.
- backend سایت Proton، retention/داده‌های session و رفتار serverless آن در repo موجود نیست؛ هیچ درخواست عملی به آن ارسال نشد.
- هیچ APK یا binary release از این سه پروژه verify/decompile نشد؛ ادعای reproducible build یا تطابق باینری با commit نداریم.
- تعداد فایل‌های test فقط از tree commit شمرده شده؛ testها اجرا یا کیفیت پوشش با runtime سنجیده نشده است.
- مجوزها بر اساس فایل/metadata عمومی بررسی شده‌اند و این گزارش مشاورهٔ حقوقی نیست.
- روی شاخهٔ `arena/128cd501-vpn-project` هیچ build یا test در این مرحله اجرا نشد؛ تغییر این مرحله فقط همین گزارش ممیزی است.
