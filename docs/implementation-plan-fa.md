# برنامهٔ مرحله‌ای تکمیل VPN Hub

**تاریخ مبنا:** ۲۰۲۶-۱۰-۰۶<br>
**شاخه:** `arena/128cd501-vpn-project`<br>
**دامنه:** بستن شکاف‌های نسخهٔ فعلی، با تمرکز بر اتصال قابل‌اعتماد Xray و معماری چندموتوره. این برنامه فقط از کانفیگ رسمی/مجاز یا کانفیگی که خود کاربر وارد می‌کند استفاده می‌کند.

> این سند backlog اجرایی است؛ وضعیت جاری و سابقهٔ فازها در [`ROADMAP.md`](../ROADMAP.md) می‌ماند. «تأییدشده روی گوشی» فقط برای همان دستگاه، شبکه و کانفیگ گزارش‌شده معتبر است؛ تست unit یا ساخته‌شدن TUN جای آزمون ترافیک واقعی را نمی‌گیرد.

## خط پایهٔ واقعی پروژه

| حوزه | وضعیت فعلی | چیزی که هنوز اثبات نشده/باید تکمیل شود |
|---|---|---|
| Xray/V2Ray | Xray embedded واقعاً اجرا می‌شود. VLESS، VMess، Trojan و Shadowsocks در subset سازگار به JSON داخلی Xray map می‌شوند؛ VLESS `httpupgrade/none` یک بار/چندبار روی گوشی `VERIFIED` شده است. | ترافیک proxy/core تأیید شده، اما ترافیک یک اپ عادی اندروید از app → Android TUN → Xray → اینترنت جداگانه تأیید نشده. transportهای دیگر ماتریس گوشی ندارند. |
| sing-box و Clash | parser/import و mapper آزمایشی وجود دارد؛ outbound سازگار به Xray تبدیل می‌شود. | engine مستقل sing-box یا Clash وجود ندارد؛ انواع ناسازگار فقط برای تشخیص/نمایش نگه داشته می‌شوند. |
| WireGuard | GoBackend داخل اپ اجرا می‌شود؛ `UP` به‌تنهایی موفقیت نیست و verifier ترافیک/egress دارد. | handshake/peer telemetry دقیق‌تر و reconnect با restart/backoff و IP جایگزین کامل نیست؛ موفقیت باید به تفکیک دستگاه/شبکه سنجیده شود. |
| OpenVPN | parser، آماده‌سازی/pinning و handoff به کلاینت خارجی وجود دارد. | engine داخلی، تصمیم مجوز/وابستگی و تست عملی handoff روی کانفیگ مجاز باقی است. |
| TUN و Android | سرویس Xray/WireGuard واقعی و مسیر مجوز/سرویس foreground وجود دارد. `AutoVpnService` فقط TUN آزمایشی می‌سازد و packetها را drop می‌کند. | Always-on/lockdown فقط راهنمای Android است، نه kill switch اختصاصی اثبات‌شده؛ IPv6، DNS leak، bypass اپ‌ها و رفتار توقف سرویس باید روی گوشی آزموده شوند. |
| Verify و UI | Xray proxy-egress، IP عمومی، بررسی DNS best-effort و traffic counters از core قابل نمایش‌اند. WireGuard معیار verifier جدا دارد. | `XRAY_PROXY_EGRESS` معادل تأیید app-to-TUN یا DNS-leak authoritative نیست. رفتار طولانی‌مدت و چنددستگاهی نهایی نشده است. |
| پروفایل و تست | raw config/subscription URL با Android Keystore + AES-GCM ذخیره می‌شود؛ UI روشن/تیرهٔ سفید/مشکی با accent محدود، آیکون سیستم VPN و traffic حفظ می‌شوند. Queue testها محدود و کاربرآغازشده‌اند؛ Smart fallback خاموش و opt-in است. | redaction سراسری، سیاست export/backup، persistence واقعی per-network و پوشش کامل lifecycle هنوز نیازمند بازبینی‌اند. |
| Build و انتشار | CI، unit tests و debug APK build موجودند؛ Xray AAR با نسخه و SHA-256 pin می‌شود؛ ABI فعلی `arm64-v8a` است. | نام و مجوز پروژه، اسناد معماری/امنیت/provider، Gradle wrapper، release signing، پشتیبانی ABI و بررسی نهایی مجوز Xray/LGPL مشخص/تکمیل نشده‌اند. |

## قواعد ثابت برای همهٔ مراحل

1. فقط کانفیگ رسمی/مجاز provider یا کانفیگی که کاربر خودش paste/import کرده؛ هیچ pool عمومی، استخراج مخفی، generator کنارگذاشته‌شده یا ارسال credential به backend اضافه نشود.
2. Quick/Real/Connect verification فقط با اقدام روشن کاربر آغاز شود. هیچ probe در startup، بازشدن صفحه، تغییر شبکه یا مرتب‌سازی/recommendation اجرا نشود. صف‌ها capped و قابل لغو بمانند.
3. Smart fallback فقط پس از لمس Connect و با فعال‌سازی قبلی کاربر مجاز است؛ حداکثر candidate محدود، بدون queue-wide scan. Recommendation و sorting از دادهٔ ذخیره‌شده استفاده کنند و probe تازه نسازند.
4. تا وقتی شواهد scope مربوط را نداریم، از عبارت «ترافیک کل گوشی تأیید شد» استفاده نکنیم. وضعیت proxy-egress، tunnel-running، app-to-TUN و DNS-leak هرکدام جدا باشند.
5. UI فعلی، جریان import → start → verify، آیکون VPN و نمایش traffic حفظ شود؛ بازطراحی سفید/مشکی و accent محدود به خواست کاربر نیاز دارد.
6. از MLM VPN و ZedSecure فقط ایدهٔ معماری؛ کد کپی نشود. Proton Generation و generator آن خارج از scope هستند.

## ترتیب اجرا

### مرحلهٔ 0 — ثبت حقیقت runtime و mapperها — همین حالا

**هدف:** مدل پروژه و UI بین «فرمت ورودی»، «mapper» و «موتور واقعی» تفاوت قائل شوند.

- [x] فهرست پایه و اولویت‌ها در این سند ثبت شد.
- [x] `EngineRegistry` به فهرست runtimeهای واقعی و routeهای ورودی جدا شد؛ sing-box/Clash دیگر به‌عنوان status-kind موتور معرفی نمی‌شوند و route آن‌ها به Xray اشاره می‌کند.
- [ ] اجرای unit testهای registry و کل مجموعه، سپس رفع regressions احتمالی؛ در این محیط به‌علت نبود Java و Gradle قابل اجرا نشد.
- [ ] diagnostics کامل‌تر برای هر profile، شامل منبع/mapper و runtime واقعی؛ OpenVPN صریحاً handoff باشد. برچسب مسیر در پیام import و فهرست profileها اصلاح شده است.

**معیار پایان:** تست‌ها پاس شوند؛ برای sing-box/Clash عبارت «mapped through Xray» و برای VLESS/VMess/Trojan/SS عبارت «Embedded Xray» دیده شود؛ OpenVPN به‌عنوان engine داخلی نمایش داده نشود.

### مرحلهٔ 1 — اثبات مسیر واقعی app-to-TUN — اولویت P0

این مهم‌ترین گیت فنی است و به اجرای دستی روی گوشی و یک کانفیگ مجاز نیاز دارد؛ از محیط کد به‌تنهایی قابل اثبات نیست.

1. ثبت دستگاه، نسخهٔ Android، نوع شبکه (Wi‑Fi/cellular)، نسخهٔ اپ و profile ID؛ بدون ثبت secret یا raw link.
2. با VPN خاموش، درخواست baseline از یک اپ عادیِ غیر-bypass به endpoint egress مورداعتماد انجام شود.
3. با زدن Connect، آیکون VPN/سرویس foreground و start شدن Xray دیده شود؛ همان اپ عادی درخواست تازه بفرستد (نه request خود MainActivity که برای جلوگیری از loop از VPN مستثناست).
4. egress واقعی آن اپ با egress مورد انتظار کانفیگ مقایسه شود؛ traffic باید هنگام همین درخواست تغییر کند. سپس با Disconnect، رفتار برگشت مسیر سنجیده شود.
5. سناریوی stop/revoke و ازسرگیری اپ/شبکه تکرار شود؛ وضعیت درست برای هر حالت `verified`, `running-unverified` یا `failed` ثبت شود.
6. پس از داشتن شواهد، scope مستقلی برای app-to-TUN اضافه شود؛ تا آن زمان `XRAY_PROXY_EGRESS` فقط همان proxy egress باقی بماند.

**معیار پایان:** ترافیک یک package عادیِ داخل VPN به‌طور قابل تکرار از تونل عبور کند، egress مستقل ثبت شود و UI scope درست را اعلام کند. این مرحله را روی دستگاه واقعی اجرا نکرده‌ایم.

### مرحلهٔ 2 — ماتریس سازگاری Xray روی گوشی — اولویت P0

**موجود در unit/config matrix:** ساخت runtime JSON و ردکردن برخی transportهای unsupported. **هنوز موجود نیست:** اثبات end-to-end روی دستگاه برای همهٔ خانه‌های ماتریس.

| خانواده/حالت | وضعیت/آزمون لازم |
|---|---|
| VLESS `httpupgrade/none` | baseline گوشی موجود؛ تکرار بعد از گیت app-to-TUN |
| VLESS TCP/none | تست گوشی |
| VLESS TCP با HTTP header camouflage | تست گوشی |
| VLESS WebSocket/TLS | تست گوشی با SNI/Host/Path واقعی |
| VLESS gRPC/TLS | تست گوشی با serviceName/authority واقعی |
| VLESS REALITY | تست گوشی با public key/short ID/SNI کامل و معتبر |
| VLESS XHTTP/SplitHTTP | تست گوشی با alias/mode واقعی provider |
| VMess | حداقل transportهای رایجِ subset پشتیبانی‌شده؛ با/بدون TLS طبق کانفیگ مجاز |
| Trojan و Shadowsocks | تست گوشی؛ SS بدون SIP003 plugin، چون plugin فعلاً عمداً رد می‌شود |
| mKCP/KCP، QUIC/HTTP3 و UDP transportها | فعلاً Connect پشتیبانی نمی‌شود؛ خطای روشن و import/diagnostic محفوظ بماند تا پیاده‌سازی و مجوز جداگانه بررسی شود |

برای هر مورد، نتیجهٔ import، build config، start، verification scope، egress، traffic و علت شکست ثبت شود؛ raw credential و link در گزارش/CI قرار نگیرد. یک unit-test موفق به‌تنهایی مورد را «پشتیبانی‌شده روی گوشی» نمی‌کند.

### مرحلهٔ 3 — DNS، IPv6 و fail-closed — اولویت P0

- [x] ورودی DNS در UI، Xray service و runtime builder به public IPv4 محدود شد؛ `localhost`/Android system DNS fallback از runtime پیش‌فرض حذف شد تا resolver نامطمئن بی‌صدا وارد مسیر نشود. در صورت در دسترس نبودن resolverهای انتخاب‌شده، اتصال ممکن است fail شود.
- [x] route policy Xray مشترک شد: Android TUN مسیرهای `0.0.0.0/0` و `::/0` را می‌گیرد و LAN bypass در runtime شامل IPv4 خصوصی/link-local و IPv6 loopback/ULA/link-local است.
- [x] راهنمای Kill switch صریح شد و دکمهٔ بازکردن Android VPN settings برای انتخاب Always-on و “Block connections without VPN” اضافه شد؛ این UI ادعای enforcement مستقل ندارد.
- [x] unit-testهای pure برای DNS/runtime route policy نوشته شدند؛ در این workspace به دلیل نبود Java/Gradle اجرا نشدند.
- [ ] DNS leak test واقعی با endpoint authoritative یا سرویس کنترل‌شده/مجاز؛ DoH-through-local-Xray فعلی فقط route check best-effort است.
- [ ] آزمون IPv4 و IPv6 جداگانه؛ اگر core مسیر IPv6 را حمل نکند، leak رخ ندهد و وضعیت/راهنما شفاف باشد.
- [ ] آزمون DNS در Wi‑Fi و cellular، با DoH سالم، مسدود و poison شده؛ Android/system fallback فعلاً خاموش است و هر fallback بعدی باید policy و scope leak را روشن کند.
- [ ] بررسی behavior هنگام crash/kill شدن engine و Android Always-on + “Block connections without VPN” (lockdown) روی گوشی.
- [ ] اعتبارسنجی packageهای bypass، package حذف‌شده/نامعتبر و این‌که package خود اپ برای جلوگیری از loop مستثناست؛ فعلاً syntax validation است و bypass لیست فقط بعد از reconnect اعمال می‌شود.
- [x] تعیین policy LAN/private IP؛ FakeDNS و Fragment تا وقتی mapping و تست ایمن ندارند خاموش/غیرفعال بمانند.

**معیار پایان:** هیچ status یا UI claim از «بدون DNS leak/kill switch کامل» پیش از شواهد device-level ارائه نشود.

### مرحلهٔ 4 — قرارداد مشترک engine و انتقال orchestration از Activity — اولویت P1

- [x] قرارداد typed `VpnEngineAdapter` برای `prepare/start/stop/status/stats/verify/explainFailure` اضافه شد؛ `verify()` فقط snapshot مدرک verifier خود سرویس است و probe تازه‌ای اجرا نمی‌کند.
- [x] adapterهای Android برای Xray و WireGuard ساخته شدند؛ آماده‌سازی config، ساخت Intent شروع/توقف، وضعیت، stats و failure snapshot از `MainActivity` بیرون رفت.
- [x] `MainActivity` حالا engine را از route registry می‌گیرد و جریان مشترک prepare/start/refresh/stop را اجرا می‌کند؛ generation فعلی درخواست و پیام UI هنوز در Activity باقی است.
- [x] snapshot واحد status/stats/verification/failure scope اضافه شد؛ config آماده‌شده و request credentials را در `toString()` افشا نمی‌کنند.
- [x] unit-testهای pure برای scope/stats/failure/redaction و stale-token/cancel/stop coordinator نوشته شدند؛ در این workspace به‌علت نبود Java/Gradle اجرا نشدند.
- [x] coordinator خالص Kotlin برای generation token، جایگزینی request قدیمی، cancel هنگام رد/stop مجوز و ثبت آخرین start request اضافه شد؛ callback آماده‌سازی/شروع فقط اگر token جاری باشد پذیرفته می‌شود و این snapshot ادعای زنده‌بودن service نیست.
- [x] درخواست Stop صریح targetهای service غیرترمینال را نگه می‌دارد؛ coordinator تا status ترمینال `STOPPED`/`IDLE`/`FAILED` در فاز `STOPPING` می‌ماند و نتیجهٔ stop ناموفق را `FAILED` ثبت می‌کند.
- [x] `MainActivity` statusهای Xray/WireGuard را در refresh زنده به coordinator می‌دهد؛ service death/revoke فقط برای engine/profile درخواستی و پس از status فعال یا تغییر از baseline پیش از start reconcile می‌شود تا status قدیمی درخواست تازه را پاک نکند.
- [x] هنگام تعویض engine، adapter قبلی stop می‌شود و start جدید تا status ترمینال همان service منتظر می‌ماند؛ stop ناموفق یا timeout fail-closed است و engine جدید را شروع نمی‌کند.
- [x] `activeConnectionProfileId` mutable از Activity حذف شد؛ `trackedConnectionProfileId` فقط از `requestedProfileId` coordinator خوانده می‌شود و دیگر state دوم نگه نمی‌دارد.
- [x] smoke instrumentation tests برای dispatch واقعی ACTION_STOP در Xray/WireGuard، با کانفیگ خالی و بدون ایجاد TUN، اضافه شدند؛ اجرا/تأیید نشده‌اند.
- [ ] افزودن تست‌های crash/revoke و switch barrier روی Android؛ اجرای instrumentation suite و تأیید مسیر روی emulator/device.

**معیار پایان:** adapterهای Xray/WireGuard و coordinator مسیر مشترک lifecycle/query داشته باشند؛ reconciliation پیاده‌شده با instrumentation/device test تأیید شود و generation/profile state از Activity بیرون بماند.

**معیار پایان:** افزودن engine جدید به یک adapter/implementation محدود شود و lifecycle در UI branchهای جداگانهٔ Xray/WireGuard پراکنده نباشد.

### مرحلهٔ 5 — پایداری شبکه و reconnect — اولویت P1

- [ ] برای Xray و WireGuard، lost network، تغییر Wi‑Fi↔cellular، app background/foreground، process/service restart و revoke به state machine یکنواخت وارد شود.
- [ ] Xray علاوه بر reverify فعلی، شرایطی را که core باید restart شود از خطای صرف verification جدا کند؛ timeout/backoff/cancel امن داشته باشد.
- [ ] WireGuard: `Tunnel.State.UP` با handshake peer یکی نیست؛ telemetry قابل‌دسترسِ peer/handshake مشخص و در صورت امکان اضافه شود.
- [ ] reconnect WireGuard با restart/backoff و انتخاب endpoint/IP جایگزین فقط از config کاربر؛ هیچ candidate جدیدی خودکار probe نشود مگر در جریان Connect یا test صریح مجاز.
- [ ] network identity فعلی فقط `cellular/wifi/vpn/ethernet` را می‌شناسد؛ اگر per-network learning لازم شد، persistence و حریم خصوصی طراحی شود، بدون برداشت location/شناسهٔ حساس بی‌دلیل.
- [ ] last-good و نتیجهٔ verification/test به تفکیک شبکهٔ قابل‌شناسایی، زمان و engine پایدار شود؛ `VpnProfile` فعلاً latest network/time را دارد، نه تاریخچهٔ کامل verified برای هر شبکه.
- [ ] route ladder محدود طراحی شود: آخرین مسیر موفق همان شبکه، config انتخابی کاربر، DNS fallback مجاز و pinning فقط وقتی protocol-safe است؛ Smart fallback فعلی حداکثر چند profile نزدیک را پس از Connect امتحان می‌کند و معادل ladder کامل نیست.
- [ ] timeout/backoff/cancel در تلاش‌های Connect یکنواخت شود؛ queue ping دستی از قبل سقف و worker موازی دارد و نباید با connect retry بی‌محدودیت اشتباه شود.
- [ ] traffic counters در session طولانی/چند دستگاه و زمان reset/reconnect سنجیده شوند.

### مرحلهٔ 6 — دادهٔ محلی و حریم خصوصی — اولویت P1

**کنترل‌های موجود:** raw config و subscription URL رمزگذاری AES-GCM با Keystore؛ `allowBackup=false` و `usesCleartextTraffic=false` در manifest؛ Diagnostics امن ادعا می‌کند raw secret را کپی نمی‌کند.

- [ ] threat model و data inventory: secret، metadata (از جمله endpoint/host)، subscription URL، نتیجهٔ test، log و export.
- [ ] تصمیم بگیریم metadata غیرمحرمانه مثل endpoint/host در SharedPreferences ساده بماند یا آن هم رمز شود؛ raw config و subscription URL همین حالا encrypted هستند.
- [ ] audit همهٔ مسیرهای exception/native-core/notification/status/clipboard تا UUID، password، private key و subscription URL بیرون نرود؛ redaction tests مستقل اضافه شود.
- [ ] تصمیم backup/restore روشن؛ در وضعیت فعلی backup خاموش است و profileها به دستگاه جدید منتقل نمی‌شوند.
- [ ] export/import عمومی فقط با انتخاب کاربر، تأیید و هشدار secret؛ OpenVPN handoff فعلی فایل متن config می‌سازد و باید همین هشدار واضح را داشته باشد.
- [ ] بررسی رفتار Keystore در reset/restore/خرابی کلید، حذف profile/subscription و حذف دادهٔ قدیمی.
- [ ] تصمیم telemetry/crash reporting و privacy policy پیش از انتشار؛ در صورت نبود policy، telemetry خاموش بماند.
- [ ] افزودن `SECURITY.md` و پاسخ مسئولانه به گزارش آسیب‌پذیری.

### مرحلهٔ 7 — UX، diagnostics و دسترس‌پذیری — اولویت P1/P2

- [ ] متن اتصال، خطا و verification بر اساس scope جدا شود: «Xray proxy egress تأیید شد» در برابر «ترافیک اپ از TUN تأیید شد».
- [ ] taxonomy خطا: مجوز Android، config ناقص، auth/credential، transport ناسازگار، endpoint/DNS، timeout، core crash و app-to-TUN/DNS leak؛ هیچ secret در پیام عادی نیاید.
- [ ] status/stats مشترک برای همهٔ runtimeهای واقعی؛ الان WireGuard و Xray بیشترین پوشش را دارند و برای OpenVPN اصلاً runtime داخلی نداریم.
- [ ] تکمیل empty/error states، accessibility و polish نهایی Settings/Locations بدون بازطراحی خلاف تم فعلی.
- [ ] profile lifecycle نهایی: انتخاب پایدار، تگ‌های اختیاری، favorite، rename/delete، import/export امن و مدیریت واضح پروفایل‌های خراب/قدیمی.
- [ ] Advanced diagnostics فنی بماند؛ Quick check، Real delay و full VPN verification جدا نمایش داده شوند.
- [ ] هر sort/recommendation از دادهٔ موجود استفاده کند؛ queue و subscription بزرگ فقط با اقدام دستی کاربر و batch cap آزمایش شوند.

### مرحلهٔ 8 — license، ساخت و آمادگی انتشار — release blocker

- [ ] انتخاب نام محصول و license پروژه.
- [ ] تکمیل `ARCHITECTURE.md`, `SECURITY.md`, `PROVIDERS.md` و privacy policy.
- [ ] بازبینی license و noticeهای AndroidLibXrayLite/Xray (یادداشت فعلی LGPL-3.0 است)، WireGuard و تمام dependencyها؛ تعیین نحوهٔ ارائهٔ notice/source متناظر قبل از انتشار.
- [ ] افزودن Gradle wrapper قابل‌تکرار و checksum distribution؛ CI از همان wrapper استفاده کند.
- [ ] نسخه‌بندی، release build، signing key خارج repo/CI secrets، checksum و دستورالعمل امضای کاربر.
- [ ] تصمیم پشتیبانی ABI: اکنون فقط `arm64-v8a`; دستگاه‌های دیگر باید با پیام درست unsupported باشند یا ABIهای مجاز/قابل‌ساخت اضافه شوند.
- [ ] pin کردن action/toolchainها و ثبت provenance/hash dependencyهای دانلودی؛ checksum فعلی Xray حفظ شود.
- [ ] اجرای CI کامل روی push/PR و Android instrumentation/device matrix؛ debug build فعلی به‌تنهایی release gate نیست.
- [ ] بررسی سیاست فروشگاه/توزیع مستقیم و آماده‌کردن notices/privacy disclosures؛ تصمیم flavorهای direct APK/F-Droid/Play را فقط با توجه به مجوز و policy بگیریم.

### مرحلهٔ 9 — توسعه‌های اختیاری، نه شرط گیت فعلی

این‌ها را تا پایان گیت‌های P0/P1 و اثبات نیاز واقعی عقب می‌اندازیم:

- engine native sing-box فقط اگر protocolهای لازم با Xray قابل map نباشند؛ در غیر این صورت همین mapper محدود و شفاف کافی است.
- engine داخلی OpenVPN فقط پس از تصمیم license، انتخاب core و آزمون کانفیگ رسمی/مجاز؛ فعلاً handoff خارجی باقی بماند.
- provider adapters فقط برای مسیر رسمی/manual/مجاز و بدون استخراج از اپ رسمی یا دورزدن محدودیت پلن.
- HTTP/SOCKS proxy chain، Psiphon، WARP، MASQUE یا Tor فقط با توجیه محصول، endpoint/مجوز معتبر و review امنیتی جدا؛ هیچ‌کدام پیش‌نیاز MVP فعلی نیستند.
- operator-aware learning یا انتخاب خودکار بزرگ‌مقیاس تا وقتی با privacy و قاعدهٔ «بدون probe بی‌اجازه» سازگار نشده، انجام نشود.

## ترتیب همین نوبت

1. جداکردن مدل mapper ورودی از runtime واقعی در `EngineRegistry` و route-driven Connect dispatch (انجام شد؛ تست هنوز اجرا نشده است).
2. اجرای unit tests و رفع خطای ناشی از refactor؛ اجرای محلی فعلاً به Java/Gradle نیاز دارد که در این محیط نیست.
3. ثبت گیت app-to-TUN به‌عنوان اولین آزمون دستگاه؛ آن را در این محیط به‌جای کاربر/device اجرا نمی‌کنیم و endpointی را probe نمی‌کنیم.
4. بعد از جمع‌شدن نتیجهٔ صریح تست گوشی، scope/کد verification را اصلاح می‌کنیم و سراغ ماتریس transport می‌رویم.
