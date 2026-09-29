# نقشه راه پروژه VPN Auto-Connector برای ایران

> این فایل «نقشه راه زنده» پروژه است. هر وقت در مسیر تصمیم مهم، تغییر فاز، یا کشف محدودیت جدید داشتیم، همین فایل را به‌روزرسانی می‌کنیم تا پروژه شلوغ و گیج‌کننده نشود.

## هدف محصول

ساخت یک اپلیکیشن اندرویدی که با **اکانت یا کانفیگ خود کاربر** کار کند و بدون اینکه ما سرور خروجی یا پهنای‌باند برای کاربران بخریم، بهترین مسیر اتصال را برای اینترنت ایران پیدا کند.

اپ قرار نیست فروشنده VPN باشد. اپ قرار است این کارها را خودکار کند:

1. گرفتن/ایمپورت کانفیگ مجاز کاربر.
2. پیدا کردن IPهای واقعی و سالم برای همان کانفیگ.
3. تست چند روش اتصال.
4. انتخاب سریع‌ترین مسیر موفق.
5. اتصال کامل دستگاه با Android VpnService.
6. مانیتورینگ، reconnect، kill switch و جلوگیری از DNS leak.

## اصل‌های ثابت پروژه

- **بدون سرور خروجی پولی از سمت ما:** ترافیک کاربران نباید از زیرساخت ما رد شود، مگر در حالت optional و user-supplied.
- **Bring Your Own Account/Config:** کاربر از اکانت، service credential، یا config مجاز خودش استفاده می‌کند.
- **عدم دور زدن محدودیت پلن‌ها:** اگر اکانت رایگان فقط چند سرور دارد، اپ فقط همان‌ها را استفاده می‌کند.
- **عدم استخراج مخفی از اپ رسمی VPNها:** استفاده فقط از manual config، API رسمی/مجاز، service credentials، یا import کاربر.
- **Credential فقط روی گوشی:** هیچ پسورد/توکن کاربر نباید به backend ما ارسال شود.
- **اتصال تأییدشده:** UI فقط وقتی Connected نشان دهد که ترافیک واقعاً از تونل عبور کرده باشد.
- **طراحی مخصوص ایران:** تصمیم‌گیری بر اساس فیلترینگ هوشمند، تفاوت اپراتورها، DNS poisoning، IP block، SNI/DPI و UDP block.

## تعریف موفقیت نسخه اولیه

نسخه اول وقتی موفق است که کاربر بتواند:

1. یک فایل OpenVPN/WireGuard یا لینک/فایل V2Ray/Xray را از فایل یا clipboard import کند.
2. اپ hostnameهای داخل config را resolve/pin کند.
3. IPهای candidate را تست کند.
4. بهترین IP/route را انتخاب کند.
5. VPNService را بالا بیاورد.
6. egress IP و DNS را verify کند.
7. اگر اتصال افتاد، اپ مسیر بعدی را امتحان کند.

## معماری هدف

```text
Android App
│
├── UI Layer
│   └── Kotlin + Jetpack Compose
│
├── VPN Platform Layer
│   ├── Android VpnService
│   ├── Foreground notification
│   ├── Always-on / lockdown compatibility
│   └── Per-app split tunneling
│
├── Secure Storage
│   ├── Android Keystore
│   ├── encrypted configs/credentials
│   └── no secret logs
│
├── Config Layer
│   ├── OpenVPN parser/renderer
│   ├── WireGuard parser/renderer
│   ├── V2Ray/Xray share-link parser/prober
│   ├── IP pinning
│   └── temporary runtime configs
│
├── Resolver + Health Checker
│   ├── DoH resolver
│   ├── optional static/signed manifest
│   ├── fake/private IP filtering
│   ├── TCP/TLS/protocol probes
│   └── per-network health cache
│
├── Route Orchestrator
│   ├── Direct route
│   ├── Pinned IP route
│   ├── Provider stealth route, where official
│   ├── User proxy chain route
│   ├── Psiphon/WARP/MASQUE/Tor rescue route, later
│   └── auto fallback ladder
│
├── Tunnel Engines
│   ├── OpenVPN engine, licensing to decide
│   ├── WireGuard engine, embedded first
│   ├── Xray/V2Ray engine, experimental embedded path added; license/integration still under review
│   ├── tun2socks / packet engine, licensing to decide
│   └── underlay engines, later
│
└── Provider Adapters
    ├── Import-only, MVP
    ├── Proton manual config
    ├── Nord manual/service credentials
    ├── Express manual config
    ├── Surfshark manual/service credentials
    └── others only if official/manual path exists
```

## وضعیت فعلی

- [x] ایده و محدودیت‌های محصول مشخص شد.
- [x] مسیر رایگان/بدون سرور خروجی انتخاب شد.
- [x] تصمیم گرفتیم ابتدا روی config/account خود کاربر کار کنیم.
- [x] تصمیم گرفتیم از تجربه MSN-GUARD، AetherST، Psiphon و Relay فقط به عنوان معماری الهام بگیریم، نه کپی کورکورانه.
- [ ] انتخاب نام پروژه.
- [ ] انتخاب license پروژه.
- [ ] انتخاب stack نهایی Android و native core.
- [x] ساخت skeleton اپ با Activity، VpnService placeholder و مدل‌های core اولیه.
- [x] شروع فاز 1: import config، parser OpenVPN/WireGuard، و renderer pinned config اضافه شد.
- [x] شروع فاز 2: DoH resolver، DNS cache، public IPv4 filtering، TCP/TLS probe و score/cache اولیه اضافه شد.
- [x] شروع فاز 3: `VpnService` واقعی با foreground notification، TUN bootstrap، route/DNS policy و socket protection پایه اضافه شد.
- [x] شروع فاز 4: اولین engine واقعی با WireGuard Android GoBackend اضافه شد؛ اجرای config پین‌شده، verification اولیه RX/TX + public egress IP، و rebind پایه روی تغییر شبکه اضافه شد.
- [x] نتیجه تست گوشی وارد شد: WireGuard با endpoint IPv4 واقعی گاهی تا وضعیت `VERIFIED` رسید، اما در شبکه‌های ایران WireGuard/UDP اغلب فیلتر است؛ DoH hostnameها روی شبکه موبایل می‌توانند به IP جعلی/private مثل `10.10.34.35` poison شوند؛ پشتیبانی از endpoint literal IPv4/IPv6 بدون DoH و fallback DoH با IP literal اضافه شد.
- [x] برای مسیر جایگزین فوری، آماده‌سازی و ذخیره config موقت OpenVPN TCP/pinned برای import در کلاینت OpenVPN اضافه شد تا قبل از embed engine داخلی هم قابل تست باشد.
- [x] با توجه به کمبود config سالم OpenVPN در ایران، import و probe اولیه لینک‌های V2Ray/Xray (`vless`, `vmess`, `trojan`, `ss`) اضافه شد؛ import از clipboard هم اضافه شد تا نیاز به تبدیل دستی لینک‌ها به `.txt` نباشد.
- [x] مسیر experimental برای اجرای embedded Xray core با AndroidLibXrayLite اضافه شد: لینک V2Ray/Xray به config JSON رسمی‌تر با TUN inbound تبدیل می‌شود، Android `VpnService` بالا می‌آید، و core با همان TUN fd شروع می‌شود. برای کنترل حجم APK تستی، فعلاً native ABI روی `arm64-v8a` محدود شده است. این مسیر هنوز نیاز به تست گوشی و review license دارد.

## فازها

### فاز 0 — تصمیم‌های پایه و آماده‌سازی repo

هدف: جلوگیری از آشفتگی قبل از کدنویسی اصلی.

- [ ] انتخاب نام موقت پروژه.
- [ ] انتخاب license: کاملاً مهم، چون OpenVPN/WireGuard/Psiphon/Tor ممکن است GPL/AGPL داشته باشند.
- [x] انتخاب حداقل نسخه Android: Android 8.0+ / minSdk 26.
- [x] انتخاب زبان پایه: Kotlin. UI فعلاً Activity ساده است؛ Compose در فاز UX اضافه می‌شود.
- [x] ساخت پروژه Android پایه.
- [ ] افزودن اسناد:
  - [x] `ROADMAP.md`
  - [ ] `ARCHITECTURE.md`
  - [ ] `SECURITY.md`
  - [ ] `PROVIDERS.md`

**خروجی فاز:** پروژه Android خالی ولی قابل build، همراه با اسناد پایه.

---

### فاز 1 — MVP Import + Config Intelligence

هدف: اپ بتواند config کاربر را بفهمد، نه اینکه هنوز حتماً VPN کامل وصل کند.

- [x] Import فایل `.ovpn` در UI اولیه و parser پایه.
- [x] Import فایل WireGuard `.conf` در UI اولیه و parser پایه.
- [x] مدل داخلی مشترک برای endpointها:

```text
ProviderProfile
ServerProfile
EndpointCandidate
ConnectionMethod
HealthResult
PinnedConfig
```

- [x] Parser ساده OpenVPN:
  - [x] `remote host port proto`
  - [x] `proto`
  - [x] `port`
  - [x] `auth-user-pass`
  - [x] `verify-x509-name`
  - [x] certificate/key blocks بدون دستکاری در renderer موقت حفظ می‌شوند
- [x] Parser ساده WireGuard:
  - [x] `[Interface]`
  - [x] `[Peer]`
  - [x] `Endpoint`
  - [x] `PublicKey` به‌عنوان بخش config حفظ می‌شود؛ فعلاً validation رمزنگاری نداریم
  - [x] `AllowedIPs` به‌عنوان بخش config حفظ می‌شود؛ فعلاً policy ندارد
- [x] Parser اولیه V2Ray/Xray برای diagnostic endpointها:
  - [x] `vless://`
  - [x] `vmess://` base64 JSON
  - [x] `trojan://`
  - [x] `ss://` برای کلاینت‌های Xray-compatible
  - [x] استخراج host/port/SNI/Host و تشخیص TLS/REALITY در حد probe
  - [x] import مستقیم از Android clipboard برای لینک‌های کپی‌شده
- [x] Renderer برای config موقت pinned.
- [ ] ذخیره امن config metadata.

**خروجی فاز:** کاربر config وارد می‌کند و اپ endpointها را استخراج و نمایش می‌دهد.

---

### فاز 2 — Resolver و IP سالم‌یاب

هدف: پیدا کردن IPهای واقعی و تست اولیه آن‌ها.

- [x] DoH resolver با چند endpoint:
  - [x] Cloudflare
  - [x] Google
  - [x] Quad9, optional
- [x] حذف IPهای reserved/private:
  - [x] `10.0.0.0/8`
  - [x] `172.16.0.0/12`
  - [x] `192.168.0.0/16`
  - [x] `127.0.0.0/8`
  - [x] multicast/reserved/test-net/CGNAT
- [x] cache با TTL، فعلاً in-memory.
- [x] TCP probe برای endpointهای TCP.
- [x] TLS probe با verify hostname اصلی، بدون خاموش کردن امنیت؛ فعلاً ابزار generic است و برای موفقیت جعلی استفاده نمی‌شود.
- [x] direct diagnostic probe برای endpointهای TCP/TLS مثل V2Ray/OpenVPN وقتی DoH در ایران reset/timeout می‌شود؛ این probe فقط تشخیصی است و IP pin محسوب نمی‌شود.
- [x] نمایش هشدار وقتی Android یک VPN فعال دیگر را گزارش می‌کند، چون probe در آن حالت ممکن است از مسیر VPN خارجی عبور کند و مسیر خام ایران را نشان ندهد.
- [x] score اولیه:

```text
score = latency + recentFailurePenalty - lastSuccessBonus
```

- [x] per-network cache پایه، فعلاً in-memory:
  - [ ] تشخیص واقعی operator/mobile vs Wi-Fi از Android `ConnectivityManager`
  - [x] last good route/IP
  - [x] failure count

**خروجی فاز:** اپ برای هر hostname چند IP پیدا می‌کند و سالم‌ترین candidateها را رتبه‌بندی می‌کند.

---

### فاز 3 — Android VpnService و تونل پایه

هدف: اپ بتواند تونل سیستم‌سطحی بسازد.

- [x] ساخت `VpnService` واقعی برای bootstrap.
- [x] ساخت foreground service و notification با دکمه Stop.
- [x] ایجاد TUN interface.
- [x] route کامل `0.0.0.0/0` برای IPv4.
- [x] DNS کنترل‌شده پایه با DNSهای public ثابت.
- [x] split tunneling پایه در policy/code، هنوز بدون UI انتخاب اپ‌ها.
- [ ] kill switch behavior کامل؛ بخش Android Always-on/Lockdown باید در UX و مستندات اضافه شود و بعد از engine واقعی verify شود.
- [x] protect کردن socketهای خود اپ برای جلوگیری از loop؛ HealthChecker حالا `SocketProtector` می‌پذیرد و `VpnServiceSocketProtector` اضافه شد.
- [x] packet pump موقت برای drain کردن TUN؛ تا قبل از engine واقعی packetها عمداً forward نمی‌شوند.

**خروجی فاز:** سرویس VPN بالا می‌آید و route سیستم را کنترل می‌کند، حتی اگر هنوز engine کامل وصل نباشد. در حالت فعلی این tunnel برای تست پلتفرم است و اینترنت را عبور نمی‌دهد تا engine OpenVPN/WireGuard اضافه شود.

---

### فاز 4 — Connection Engine اول

هدف: یک مسیر واقعی وصل شود.

گزینه‌ها باید از نظر license بررسی شوند:

- OpenVPN:
  - [ ] بررسی OpenVPN 3 Core / ics-openvpn / سایر گزینه‌ها.
  - [ ] تصمیم license.
  - [x] آماده‌سازی config pinned برای handoff خارجی به OpenVPN client، با اولویت TCP/443.
  - [ ] اجرای داخلی config pinned بعد از تصمیم engine/license.
  - [ ] تشخیص handshake موفق.
- WireGuard:
  - [x] بررسی اولیه WireGuard Android tunnel library / GoBackend.
  - [x] تصمیم اولیه license: dependency رسمی `com.wireguard.android:tunnel` با Apache-2.0 مناسب‌تر از گزینه‌های GPL برای شروع MVP است؛ license پروژه اصلی هنوز باید جداگانه انتخاب شود.
  - [x] افزودن dependency رسمی و سرویس foreground-aware برای WireGuard.
  - [x] اجرای config pinned: قبل از start، endpoint دامنه‌ای با DoH به public IPv4 تبدیل و config runtime ساخته می‌شود؛ endpointهای literal public IPv4/IPv6 بدون resolve دوباره با همان config اصلی اجرا می‌شوند.
  - [x] verification اولیه: بعد از `Tunnel.State.UP`، RX/TX statistics و public HTTPS egress IP بررسی می‌شود؛ فقط در صورت موفقیت وضعیت `VERIFIED` ثبت می‌شود.
  - [x] rebind پایه روی تغییر شبکه: `ConnectivityManager` تغییر Wi‑Fi/mobile/capabilities را می‌گیرد، underlying network را refresh می‌کند، WireGuard را rebind می‌کند و verification را دوباره اجرا می‌کند.
  - [ ] تشخیص دقیق‌تر handshake/peer-level telemetry اگر API کافی بدهد؛ فعلاً معیار عملی RX/TX + egress است.
  - [ ] reconnect کامل با restart/backoff و انتخاب IP بعدی در صورت شکست rebind.
- Xray/V2Ray:
  - [x] parser/prober اولیه برای share linkهای user-supplied.
  - [x] اجرای experimental داخلی برای VLESS/VMess/Trojan/SS با AndroidLibXrayLite و TUN inbound.
  - [x] حفظ SNI/Host/Path/ALPN/Fingerprint/REALITY publicKey/shortId در config runtime تا حد parser فعلی.
  - [ ] تست گوشی روی چند لینک واقعی ایران و اصلاح config generator برای transportهای خاص.
  - [ ] review کامل license/LGPL و noticeهای production.
  - [ ] verification قوی‌تر با egress IP و جلوگیری از leak.

تصمیم اجرایی: برای اولین اتصال واقعی، WireGuard انتخاب شد چون embeddable Android tunnel library رسمی و Apache-2.0 دارد و سریع‌تر از OpenVPN قابل embed بود؛ اما تست ایران نشان داد WireGuard/UDP نمی‌تواند مسیر اصلی باشد. اولویت عملی بعدی OpenVPN TCP/443 و Xray/V2Ray-compatible fallback است؛ به همین دلیل embedded Xray به شکل experimental اضافه شد.

**خروجی فاز:** با یک config واقعی user-supplied WireGuard، engine می‌تواند config پین‌شده را بالا بیاورد و بعد از RX/TX + egress verification وضعیت verified را جدا از صرفاً UP بودن engine ثبت کند. برای ایران، app همچنین می‌تواند OpenVPN TCP/pinned config را برای تست در کلاینت OpenVPN آماده و ذخیره کند، endpointهای V2Ray/Xray سالم را تشخیص اولیه بدهد، و به‌صورت experimental همان لینک‌های V2Ray/Xray را با embedded Xray core شروع کند.

---

### فاز 5 — Verified Connection و UX ساده

هدف: کاربر گیج نشود و اتصال دروغین نبینید.

- [ ] صفحه اصلی بسیار ساده:
  - [ ] Import config
  - [ ] Country/server
  - [ ] Auto Connect
  - [ ] Status
- [ ] وضعیت‌ها:

```text
Idle
Resolving
Testing IPs
Connecting
Verifying
Connected
Reconnecting
Failed
```

- [ ] verification:
  - [ ] egress IP check
  - [ ] DNS check
  - [ ] tunnel traffic check
- [ ] نمایش خلاصه ساده:

```text
Connected via pinned IP
Connected via fallback
No working path
Credentials failed
Config stale
```

**خروجی فاز:** اتصال از دید کاربر ساده و قابل اعتماد است.

---

### فاز 6 — Auto Route Orchestrator

هدف: اپ خودش چند روش را سریع تست کند.

- [ ] route ladder:

```text
1. last good route
2. direct original endpoint
3. direct pinned IP
4. fresh pinned IPs
5. alternate port/proto from config/provider
6. user proxy chain
7. rescue underlay, later
```

- [ ] parallel probing با limit.
- [ ] cancel کردن probeهای اضافه بعد از موفقیت.
- [ ] timeout هوشمند.
- [ ] backoff برای IPهای خراب.
- [ ] reconnect خودکار روی drop.

**خروجی فاز:** Auto Connect واقعاً خودکار و سریع می‌شود.

---

### فاز 7 — Provider Adapters رسمی/مجاز

هدف: کاربر کمتر دستی config وارد کند، ولی فقط در محدوده مجاز providerها.

اولویت پیشنهادی:

1. Import-only برای همه.
2. Surfshark manual/service credentials.
3. Nord manual/service credentials.
4. Express manual config.
5. Proton manual config.
6. Windscribe فقط مسیرهای مجاز و با توضیح محدودیت free/paid.

هر adapter باید مشخص کند:

```text
supportsFreeManualConfig: yes/no/unknown
supportsOpenVPN: yes/no
supportsWireGuard: yes/no
requiresServiceCredentials: yes/no
serverListSource: manual/import/official/api/static
```

**خروجی فاز:** اپ برای چند provider محبوب مسیر رسمی و قابل توضیح دارد.

---

### فاز 8 — Rescue/Underlay رایگان یا user-supplied

هدف: وقتی IP provider مستقیم از ایران بسته است، مسیر واسط بدون هزینه سرور خودمان فراهم شود.

گزینه‌ها:

- [ ] User-supplied HTTP/SOCKS proxy chain.
- [ ] Psiphon core, بعد از بررسی license و embed feasibility.
- [ ] WARP/WireGuard-based underlay، اگر مجاز و عملی.
- [ ] MASQUE, اگر endpoint قابل استفاده و مجاز داریم.
- [ ] Tor/bridges برای fallback خاص، با توضیح سرعت کمتر و محدودیت UDP.

مدل اتصال:

```text
Phone -> Underlay -> Provider endpoint -> Internet
```

**خروجی فاز:** اگر direct provider بسته بود، اپ می‌تواند provider را از داخل مسیر واسط وصل کند.

---

### فاز 9 — امنیت، حریم خصوصی، انتشار

- [ ] threat model.
- [ ] no secret logging.
- [ ] encrypted storage.
- [ ] export/import امن configها.
- [ ] privacy policy.
- [ ] crash report policy, پیش‌فرض خاموش یا بدون secret.
- [ ] reproducible-ish builds یا حداقل signed releases.
- [ ] build flavors:
  - [ ] F-Droid/open-source compatible, اگر license اجازه دهد.
  - [ ] APK direct release.
  - [ ] Play Store فقط اگر policy اجازه دهد.

**خروجی فاز:** نسخه قابل اعتماد برای کاربران عمومی.

## Backlog کوتاه و مرتب

### اکنون

- [x] ساخت skeleton Android.
- [ ] انتخاب license.
- [x] انتخاب اولین engine: WireGuard Android GoBackend برای MVP اولیه.
- [x] پیاده‌سازی import/parser config پایه.
- [x] resolver و IP pinning پایه.
- [x] health checker اولیه.
- [x] VpnService و TUN bootstrap پایه.
- [x] انتخاب و embed اولین engine: WireGuard.
- [x] verification اولیه WireGuard با RX/TX + egress IP.
- [x] rebind پایه WireGuard روی تغییر شبکه.
- [x] تست گوشی اولیه و pivot عملی: WireGuard/UDP در ایران unreliable/filtered است؛ OpenVPN TCP fallback باید جلو بیاید.
- [x] آماده‌سازی و ذخیره config pinned OpenVPN برای handoff خارجی.
- [x] import/probe اولیه V2Ray/Xray share linkها برای وقتی پیدا کردن OpenVPN سالم سخت است.
- [x] start experimental embedded Xray engine برای لینک‌های V2Ray/Xray user-owned.

### بعدی

- [ ] تست گوشی با V2Ray/Xray سالم user-owned: import از clipboard، Resolve & probe، سپس Start imported VPN engine.
- [ ] اصلاح runtime config generator برای هر transport واقعی که در تست گوشی fail می‌شود.
- [ ] تست گوشی با OpenVPN TCP/443 pinned config در یک کلاینت OpenVPN، اگر config سالم پیدا شد.
- [ ] انتخاب license/engine برای OpenVPN داخلی.
- [ ] review نهایی license/notice برای Xray/V2Ray داخلی.
- [ ] ذخیره امن config metadata.
- [ ] تشخیص network واقعی و persistent health cache.
- [ ] UI/مستندات Always-on و lockdown/kill switch.
- [ ] reconnect کامل با restart/backoff و انتخاب IP بعدی در صورت شکست.

### بعداً

- [ ] provider adapters.
- [ ] Psiphon/WARP/MASQUE/Tor rescue.
- [ ] operator-aware learning.
- [ ] public release.

## ریسک‌های اصلی

| ریسک | توضیح | راه کنترل |
| --- | --- | --- |
| License | OpenVPN/WireGuard/Xray/Psiphon/Tor ممکن است licenseهای ناسازگار با مدل محصول داشته باشند | قبل از embed تصمیم license بگیریم |
| Provider ToS | بعضی providerها API مخفی/اپ رسمی را نمی‌پذیرند | فقط manual/official/import |
| Free account limits | اکانت رایگان همه کشورها را ندارد | محدودیت‌ها را شفاف و enforce کنیم |
| IP block کامل | اگر همه IPها و underlayها بسته باشند، اتصال ممکن نیست | خطای صادقانه + fallbackهای optional |
| DNS leak | Android DNS اگر درست route نشود لو می‌رود | forced DNS + verification |
| UDP/QUIC | همه مسیرها UDP را خوب حمل نمی‌کنند | per-protocol handling + fallback TCP |
| اعتماد کاربر | اپ credential می‌گیرد | Keystore، no backend secrets، open code تا حد امکان |


## تصمیم‌های گرفته‌شده

- Android حداقل نسخه 8.0 / API 26 برای شروع انتخاب شد.
- پکیج موقت پروژه `com.vpnproject.app` است تا بعداً بعد از انتخاب نام محصول تغییر کند.
- CI اولیه با GitHub Actions ساخته شد و فعلاً با Gradle نصب‌شده در workflow اجرا می‌شود؛ wrapper بعداً اضافه می‌شود اگر لازم شد.
- UI فعلاً ساده و بدون Compose است تا build سریع و پایدار شود؛ Compose در فاز UX اضافه می‌شود.
- Resolver فعلاً فقط public IPv4/A record را از DoH می‌پذیرد؛ IPv6 و DNSهای غیرعمومی تا زمان طراحی کامل route policy کنار گذاشته می‌شوند.
- Health probe فعلی برای TCP قابل اتکاتر است؛ برای WireGuard/OpenVPN UDP باید در فاز engine، handshake واقعی معیار موفقیت باشد.
- `VpnService` bootstrap یک تونل تستی می‌سازد و full-route/DNS را کنترل می‌کند، اما packetها را forward نمی‌کند؛ بنابراین نباید به‌عنوان اتصال موفق نمایش داده شود.
- اولین engine واقعی برای MVP، WireGuard Android GoBackend است. چون WireGuard عموماً UDP است، برای ایران همچنان باید fallbackهای OpenVPN TCP/chain/rescue را بعداً اضافه کنیم.
- معیار فعلی اتصال verified برای WireGuard این است: engine در حالت UP باشد، RX/TX stats حرکت کند، و یک HTTPS public egress IP endpoint پاسخ public IPv4 بدهد. اگر فقط UP باشد، UI نباید Connected قطعی نشان دهد.
- WireGuard service حالا روی تغییر شبکه، underlying network را refresh می‌کند و verification را دوباره اجرا می‌کند؛ اگر این rebind شکست بخورد، مرحله بعدی restart/backoff و انتخاب IP جایگزین است.
- تست گوشی نشان داد DNS poisoning می‌تواند حتی hostnameهای DoH مثل `cloudflare-dns.com`، `dns.google` و `dns.quad9.net` را به IP خصوصی `10.10.34.35` ببرد؛ بنابراین resolver باید endpointهای IP-literal DoH را ترجیح دهد و هرگز private resolver IP را قبول نکند.
- با توجه به فیلتر بودن WireGuard/UDP در ایران، مسیر MVP نباید WireGuard-only باشد؛ OpenVPN TCP/443، Xray/V2Ray-compatible configs و بعداً stealth/underlay باید به fallback ladder اضافه شوند.
- به خاطر سخت بودن پیدا کردن config سالم OpenVPN در ایران، V2Ray/Xray اول به عنوان مسیر diagnostic/probe اضافه شد و سپس یک embedded Xray engine experimental برای شروع واقعی همان لینک‌ها اضافه شد.
- تست گوشی V2Ray نشان داد DoH همچنان reset/timeout یا به `10.10.34.35` poison می‌شود، ولی direct system DNS probe برای endpointهای V2Ray مثل `dns.all.ultradns.space:8880` و hostnameهای Connectoo با TCP موفق شد؛ latency نمونه‌ها از حدود 132ms تا 4163ms بود. بنابراین fallback مستقیم برای diagnostic ارزشمند است. حالا باید همان لینک‌ها با Start imported VPN engine تست شوند تا ببینیم TUN + Xray runtime config واقعاً verified می‌شود یا کدام transport نیاز به اصلاح دارد. اگر آیکن VPN بالای گوشی فعال باشد، نتیجه probe ممکن است از مسیر VPN خارجی باشد و باید با VPN خاموش هم تکرار شود.

## تصمیم‌های باز

1. نام پروژه چیست؟
2. اپ کاملاً open-source باشد یا core باز و UI بسته؟
3. آیا با licenseهای GPL/AGPL یا licenseهای engineهای proxy مشکلی داریم، مخصوصاً برای OpenVPN/Xray/Psiphon/Tor future engines؟
4. آیا از ابتدا Psiphon را embed کنیم یا بعد از MVP؟
5. manifest/update list روی چه بستری باشد؟ GitHub Pages، Cloudflare Pages، یا بدون backend؟

## قانون به‌روزرسانی این نقشه راه

- هر فاز که شروع شد، وضعیت آن را اینجا تغییر می‌دهیم.
- هر تصمیم مهم به بخش «تصمیم‌های باز» یا «تصمیم‌های گرفته‌شده» اضافه می‌شود.
- اگر مسیر عوض شد، دلیلش نوشته می‌شود.
- هیچ feature جدیدی وارد کدنویسی نمی‌شود مگر اینجا در backlog یا فاز مربوطه ثبت شده باشد.
