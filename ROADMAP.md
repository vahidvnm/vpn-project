# نقشه راه پروژه VPN Hub / Auto-Connector چندموتوره

> این فایل «نقشه راه زنده» پروژه است. هر وقت در مسیر تصمیم مهم، تغییر فاز، یا کشف محدودیت جدید داشتیم، همین فایل را به‌روزرسانی می‌کنیم تا پروژه شلوغ و گیج‌کننده نشود.

## هدف محصول

ساخت یک اپلیکیشن اندرویدی **VPN Hub / Auto-Connector چندموتوره** که با **اکانت یا کانفیگ خود کاربر** کار کند و بدون اینکه ما سرور خروجی یا پهنای‌باند برای کاربران بخریم، بهترین مسیر اتصال را پیدا کند. ایران اولین و سخت‌ترین سناریوی محصول است، اما اسکلت باید عمومی و چندکاره باشد تا بعداً برای کاربران دیگر، providerهای مختلف و چند پروتکل هم توسعه‌پذیر بماند.

اپ قرار نیست فروشنده VPN باشد. «همه‌کاره» بودن در این پروژه یعنی یک کلاینت/ارکستریتور چندپروتکلی برای کانفیگ‌های مجاز کاربر، نه ساخت سرویس VPN پولی با سرورهای ما. اپ قرار است این کارها را خودکار کند:

1. گرفتن/ایمپورت کانفیگ مجاز کاربر.
2. پیدا کردن IPهای واقعی و سالم برای همان کانفیگ.
3. تست چند روش اتصال.
4. انتخاب سریع‌ترین مسیر موفق.
5. اتصال کامل دستگاه با Android VpnService.
6. مانیتورینگ، reconnect، kill switch و جلوگیری از DNS leak.
7. مدیریت چند profile/engine در یک اپ، با UI ساده Connect/Disconnect و diagnostics جداگانه.

## اصل‌های ثابت پروژه

- **بدون سرور خروجی پولی از سمت ما:** ترافیک کاربران نباید از زیرساخت ما رد شود، مگر در حالت optional و user-supplied.
- **Bring Your Own Account/Config:** کاربر از اکانت، service credential، یا config مجاز خودش استفاده می‌کند.
- **عدم دور زدن محدودیت پلن‌ها:** اگر اکانت رایگان فقط چند سرور دارد، اپ فقط همان‌ها را استفاده می‌کند.
- **عدم استخراج مخفی از اپ رسمی VPNها:** استفاده فقط از manual config، API رسمی/مجاز، service credentials، یا import کاربر.
- **Credential فقط روی گوشی:** هیچ پسورد/توکن کاربر نباید به backend ما ارسال شود.
- **اتصال تأییدشده:** UI فقط وقتی Connected نشان دهد که ترافیک واقعاً از تونل عبور کرده باشد.
- **طراحی مخصوص ایران:** تصمیم‌گیری بر اساس فیلترینگ هوشمند، تفاوت اپراتورها، DNS poisoning، IP block، SNI/DPI و UDP block.

## تعریف «VPN همه‌کاره» در این پروژه

منظور از همه‌کاره بودن، پشتیبانی تدریجی از چند خانواده اتصال در یک اپ واحد است:

- **Profile manager محلی:** چند config از clipboard/file ذخیره و مدیریت شود، بدون ارسال secret به backend.
- **Engineهای چندگانه:** Xray/V2Ray به‌عنوان مسیر عملی MVP ایران، WireGuard برای شبکه‌هایی که UDP کار می‌کند، OpenVPN TCP به‌عنوان handoff و بعداً engine داخلی، و user-supplied proxy/underlay در آینده.
- **یک دکمه Connect:** کاربر مجبور نباشد بداند کدام موتور باید اجرا شود؛ app بر اساس نوع config و health قبلی انتخاب کند.
- **Diagnostics پیشرفته جدا از UI عادی:** کاربر عادی فقط نتیجه ساده ببیند؛ اطلاعات DoH، probe، stats، error class و transport در بخش Advanced بماند.
- **Auto-orchestration:** app به‌تدریج last good route، transport سالم، DNS fallback و reconnect/backoff را یاد بگیرد.
- **قابل توسعه، نه شکننده:** هر protocol/engine پشت abstraction مشترک status/stats/start/stop/verify قرار بگیرد تا بعداً engine جدید اضافه شود.

مرزها همچنان ثابت‌اند: استخراج مخفی config از اپ‌های دیگر، سوءاستفاده از planها، یا عبور ترافیک کاربران از سرورهای ما جزو محصول نیست.

## تعریف موفقیت نسخه اولیه

نسخه اول وقتی موفق است که کاربر بتواند:

1. یک فایل OpenVPN/WireGuard یا لینک/فایل V2Ray/Xray را از فایل یا clipboard import کند.
2. اپ hostnameهای داخل config را resolve/pin کند.
3. IPهای candidate را تست کند.
4. بهترین IP/route را انتخاب کند.
5. VPNService را بالا بیاورد.
6. egress IP و DNS را verify کند.
7. اگر اتصال افتاد، اپ مسیر بعدی را امتحان کند.

## نقطه عطف فعلی — اتصال Xray تأییدشده روی گوشی

در تست گوشی 2026-09-30، مسیر embedded Xray برای اولین بار end-to-end تأیید شد:

- config کاربر از clipboard import شد و endpoint با direct Android/system DNS قابل دسترس بود، در حالی که DoH عمومی همچنان reset/timeout یا به IP خصوصی poison می‌شد.
- Android VPN icon فعال شد و `VpnService` + TUN واقعی بالا آمد.
- Xray core داخلی با AndroidLibXrayLite اجرا شد.
- لینک VLESS `httpupgrade/none` کار کرد و status به `VERIFIED` رسید.
- verification با `https://www.gstatic.com/generate_204` چند بار موفق شد؛ latency مشاهده‌شده 126 تا 133ms بود.
- آمار traffic از core برگشت: `proxy,downlink,44249;proxy,uplink,28698;`.

نتیجه محصولی: برای ایران، مسیر عملی MVP از «WireGuard-first» به **BYO V2Ray/Xray-first برای configهای کاربر** تغییر کرد. WireGuard و OpenVPN همچنان پشتیبانی/مسیر جایگزین هستند، اما اتصال تأییدشده فعلی روی embedded Xray است.

## معماری هدف

```text
Android App
│
├── UI Layer
│   ├── Simple Connect/Disconnect UX
│   ├── Profile list / import / paste
│   ├── Advanced diagnostics
│   └── Kotlin + Jetpack Compose, later
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
├── Profile + Config Layer
│   ├── Local profile manager
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
│   ├── Engine abstraction: start/stop/status/stats/verify
│   ├── Xray/V2Ray engine, verified embedded path for MVP ایران
│   ├── WireGuard engine, embedded but secondary where UDP works
│   ├── OpenVPN engine, licensing to decide
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
- [x] مسیر embedded Xray core با AndroidLibXrayLite اضافه و با تست گوشی تأیید شد: لینک V2Ray/Xray به config JSON با TUN inbound تبدیل می‌شود، Android `VpnService` بالا می‌آید، core با همان TUN fd شروع می‌شود، و VLESS `httpupgrade/none` کاربر تا وضعیت `VERIFIED` رسید. برای کنترل حجم APK تستی، فعلاً native ABI روی `arm64-v8a` محدود شده است. این مسیر هنوز برای transportهای بیشتر، UX، storage امن و review license نیاز به hardening دارد.
- [x] جهت محصول بعد از تست موفق Xray بازتعریف شد: اسکلت باید به یک VPN Hub چندموتوره و همه‌کاره تبدیل شود؛ MVP عملی برای ایران Xray/V2Ray-first است، اما معماری باید OpenVPN، WireGuard و underlay/proxyهای آینده را هم تمیز پشتیبانی کند.

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
  - [x] اجرای داخلی برای VLESS/VMess/Trojan/SS با AndroidLibXrayLite و TUN inbound.
  - [x] رفع مشکلات واقعی startup روی گوشی: حفظ خطای `FAILED`، ساخت XUDP base key معتبر ۳۲ بایتی، و auto-refresh وضعیت engine.
  - [x] حفظ SNI/Host/Path/ALPN/Fingerprint/REALITY publicKey/shortId در config runtime تا حد parser فعلی.
  - [x] پشتیبانی runtime و تست موفق VLESS `httpupgrade/none` با status `VERIFIED` روی شبکه موبایل ایران.
  - [x] verification اولیه Xray با delay check و traffic stats از core.
  - [x] re-verification اولیه Xray روی تغییر شبکه با debounce و refresh کردن underlying network.
  - [ ] test matrix گوشی برای transportهای بیشتر: `ws/tls`, `tcp/none`, `tcp` با HTTP header, `grpc/tls`, `reality`, `xhttp`, `vmess`, `trojan`, `ss`.
  - [x] تشخیص اولیه transport failure با پیام‌های قابل فهم‌تر برای UDP/KCP/QUIC و security unsupported؛ credential/server failure دقیق‌تر هنوز نیازمند طبقه‌بندی runtime logs است.
  - [ ] review کامل license/LGPL و noticeهای production.
  - [x] verification قوی‌تر Xray با public egress IP و DNS-route check از مسیر loopback proxy داخلی؛ DNS leak test کامل‌تر هنوز به endpoint اختصاصی/قابل‌اعتماد نیاز دارد.

تصمیم اجرایی: برای اولین engine، WireGuard انتخاب شد چون embeddable Android tunnel library رسمی و Apache-2.0 دارد و سریع‌تر از OpenVPN قابل embed بود؛ اما تست ایران نشان داد WireGuard/UDP نمی‌تواند مسیر اصلی باشد. بعد از تست موفق `VERIFIED` با embedded Xray، مسیر عملی MVP برای ایران به Xray/V2Ray-compatible configs با config کاربر تغییر کرده است. OpenVPN TCP/443 هنوز fallback/handoff مهم است و WireGuard برای شبکه‌هایی که UDP کار می‌کند باقی می‌ماند.

**خروجی فاز:** app اکنون هم WireGuard user-supplied را با verification جدا از `UP` اجرا می‌کند، هم OpenVPN TCP/pinned config را برای handoff خارجی آماده می‌کند، و مهم‌تر از همه یک config واقعی V2Ray/Xray کاربر را با embedded Xray core روی Android `VpnService` تا وضعیت `VERIFIED` وصل کرده است. فاز بعدی تبدیل این مسیر debug/prototype به UX ساده و پایدار است.

---

### فاز 4.5 — تبدیل اسکلت به VPN Hub چندموتوره

هدف: بعد از اثبات اتصال Xray، اسکلت فعلی را از صفحه تست/debug به foundation یک VPN همه‌کاره تبدیل کنیم.

- [x] تعریف مدل پایدار اولیه `VpnProfile`:
  - [x] kind: `XRAY`, `WIREGUARD`, `OPENVPN`, `UNKNOWN`؛ `PROXY_CHAIN` و future بعداً
  - [x] display name, endpoint metadata, created/updated time
  - [x] encrypted raw config / secret fields در `SecureProfileStore`
  - [x] non-secret endpoint metadata برای probe و نمایش
  - [ ] tags, favorite و last verified network/time در UI و persistence کامل
- [ ] تعریف abstraction مشترک engine:

```text
prepare(profile)
start(profile)
stop(profile)
status()
stats()
verify()
explainFailure()
```

- [x] تبدیل اولیه statusهای WireGuard و Xray به summary مشترک UI با `VpnHubStatusMapper`.
- [x] ساخت `EngineRegistry` اولیه برای انتخاب engine بر اساس نوع profile.
- [x] جداسازی اولیه diagnostics از مسیر connect عادی:
  - [x] import/probe report طولانی در Advanced diagnostics
  - [x] شروع صفحه status ساده با `Hub status` بالای diagnostics
  - [x] صفحه اصلی ساده با کارت اتصال، پروفایل، و action اصلی
  - [x] navigation اولیه سه‌بخشی: خانه، پروفایل‌ها، ابزار/diagnostics
  - [ ] صفحه اصلی نهایی با drawer/settings کامل و polish گرافیکی
- [x] storage امن اولیه برای raw configها:
  - [x] Android Keystore + AES-GCM روی SharedPreferences
  - [ ] عدم log کردن secretها در همه مسیرهای آینده
  - [ ] redaction کامل در error/report
- [ ] profile lifecycle:
  - [x] add/import و Load latest profile
  - [x] profile list قابل انتخاب اولیه با دکمه‌های dynamic در UI ساده فعلی
  - [ ] profile list نهایی با طراحی بهتر/scroll و انتخاب پایدار
  - [x] rename selected profile در UI و persistence
  - [x] delete selected profile در UI با confirm dialog
  - [x] mark/unmark favorite در UI و persistence
  - [x] ثبت اولیه `last-good/last verified` بعد از اتصال VERIFIED
  - [ ] نمایش/منطق کامل last verified برای همه engineها و شبکه‌ها
  - [ ] export فقط با هشدار کاربر

**خروجی فاز:** app دیگر فقط proof-of-concept اتصال نیست؛ یک هسته چندموتوره قابل توسعه دارد که UI و orchestrator روی آن ساخته می‌شوند.

---

### فاز 5 — Verified Connection و UX ساده

هدف: مسیر موفق Xray را از حالت debug به تجربه کاربری قابل فهم تبدیل کنیم؛ کاربر گیج نشود و اتصال دروغین نبیند.

- [x] اصل verification واقعی برای Xray پیاده و روی گوشی تأیید شد: فقط وقتی status `VERIFIED` می‌شود که HTTP check از داخل proxy موفق باشد و stats core حرکت کند.
- [x] شروع صفحه اصلی بسیار ساده:
  - [x] Import config / Paste link
  - [x] Profile list
  - [x] Connect / Disconnect با یک دکمه اصلی dynamic
  - [x] Status ساده: `Connected`, `Connecting`, `Failed` و summary مشترک Hub
  - [x] نمایش traffic خلاصه Xray از stats واقعی core
  - [x] live refresh خودکار dashboard/status هنگام اتصال
  - [x] ثبت و نمایش اولیه Last good profile بعد از Xray VERIFIED
  - [x] دکمه Diagnostics جدا از مسیر کاربر عادی
  - [x] navigation اولیه بین Home / Profiles / Tools برای کمتر شدن شلوغی صفحه
  - [ ] polish نهایی UI، settings/drawer، empty states بهتر
- [x] تفکیک اولیه UI عادی از UI debug:
  - [x] کاربر عادی اول نتیجه و علت ساده را ببیند.
  - [x] متن‌های طولانی DoH/probe/stats در Advanced diagnostics قرار بگیرد.
  - [ ] تکمیل redaction و کوتاه‌سازی پیام‌ها برای انتشار عمومی
- [ ] وضعیت‌های داخلی engine:

```text
Idle
Imported
Probing
Connecting
Verifying
Connected/Verified
Running but unverified
Reconnecting
Failed
```

- [ ] verification تکمیلی:
  - [x] Xray HTTP delay check + core traffic stats.
  - [x] public egress IP check بعد از Xray با HTTP proxy لوکال داخل همان runtime.
  - [x] DNS route check best-effort از مسیر Xray/DoH؛ leak test authoritative واقعی هنوز باقی است.
  - [x] نمایش RX/TX خلاصه برای Xray از `queryAllOutboundTrafficStats`.
  - [x] live polling اولیه traffic counter از Xray service به dashboard.
  - [x] جلوگیری از تکرار چندباره lineهای `Stats:` در Advanced diagnostics.
  - [ ] پایدارسازی/تست طولانی traffic counter روی چند دستگاه.
- [ ] پیام‌های خلاصه قابل فهم:

```text
Connected through your V2Ray/Xray config
Endpoint reachable but credentials/transport failed
DNS-over-HTTPS blocked; using Android DNS fallback
No working path for this config
This network likely blocks UDP/WireGuard
```

**خروجی فاز:** اتصال از دید کاربر ساده و قابل اعتماد است، در حالی که diagnostics پیشرفته برای debug ایران باقی می‌ماند.

---

### فاز 6 — Auto Route Orchestrator

هدف: اپ خودش چند روش را سریع تست کند.

- [ ] route ladder:

```text
1. last verified profile/route on this network
2. V2Ray/Xray direct original endpoint with full transport metadata
3. Android/system DNS fallback when DoH is blocked or poisoned
4. direct pinned IP only when protocol safely allows it
5. fresh pinned IPs for OpenVPN/WireGuard style endpoints
6. alternate port/proto from config/provider
7. user proxy chain
8. rescue underlay, later
```

- [ ] parallel probing با limit.
- [ ] cancel کردن probeهای اضافه بعد از موفقیت.
- [ ] timeout هوشمند.
- [ ] backoff برای IPهای خراب.
- [ ] reconnect خودکار روی drop.
- [x] MVP گروه subscribe: اضافه‌کردن subscription URL کاربر از دکمه +، ذخیره encrypted URL، fetch/update دستی، تبدیل هر node به profile جداگانه، و نمایش گروه‌ها بدون نشان‌دادن URL/secret.
- [ ] انتخاب خودکار بین چند لینک subscription بدون ذخیره/نمایش secret اضافه.
- [ ] ثبت last verified Xray transport per network/operator.

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
- [x] embedded Xray engine برای لینک‌های V2Ray/Xray user-owned.
- [x] اولین اتصال `VERIFIED` روی گوشی با VLESS `httpupgrade/none` user-owned.

### بعدی

- [x] شروع تبدیل اسکلت به VPN Hub چندموتوره: `VpnProfile`, `SecureProfileStore`, `EngineRegistry` اولیه.
- [x] شروع status مشترک و انتخاب profile از لیست.
- [ ] تکمیل status/stats مشترک برای همه engineها و طراحی UI نهایی.
- [x] تبدیل اولیه UI از صفحه debug به تجربه ساده Connect/Disconnect برای Xray-first MVP.
- [x] ساخت profile list و ذخیره امن metadata/configها روی گوشی.
- [x] navigation اولیه Home / Profiles / Tools و fix نمایش traffic Xray در dashboard.
- [x] live dashboard refresh و delete selected profile اولیه.
- [x] compact کردن Advanced diagnostics تا statsهای Xray هر ۲ ثانیه تکراری جمع نشوند.
- [x] rename/favorite اولیه برای profile manager.
- [x] ثبت اولیه Last good / Last verified برای پروفایل Xray موفق.
- [x] sync کردن Last good هنگام refresh/status/profile-tab تا نمایش آن بعد از VERIFIED پایدارتر شود.
- [x] شروع redesign ظاهری reference-style با hero card، power button، protocol grid و bottom navigation.
- [x] pass دوم redesign: bottom nav ثابت، حذف ظاهر debug از Tools/Profiles، stats کارت‌بندی‌شده، LTR layout برای متن انگلیسی، و حذف glyphهای مشکل‌دار از power button.
- [x] pass سوم redesign: حذف N/PRO/SET و subtitle غیرضروری، اضافه کردن دکمه + برای clipboard/file import، و جمع‌کردن protocol/quick options پشت فلش پایین تا تصمیم نهایی پروتکل‌ها.
- [x] اضافه کردن Add subscription URL به دکمه + و لیست گروه‌های subscription در Profiles.
- [x] pass چهارم redesign: چیدمان Home به سبک تصویر مرجع با هدر لوگو، hero تصویری/کوهستانی، کارت‌های Protected/Stats، دکمه power حلقه‌ای، کارت location/profile، protocol grid پنج‌ستونه و bottom nav چهارآیتمی.
- [x] pass پنجم redesign: انتقال protocol grid پشت فلش شیک بدون متن، اضافه‌کردن ناحیه Selected configs، و افزودن Auto test با toggle روشن/خاموش و تست دستی.
- [x] pass ششم redesign: کوچک‌کردن hero کانکت، حذف نام/لوگوی مرجع، تبدیل کارت کشور/کانفیگ به selector کشویی داخل همان صفحه Home، و حذف ورودی‌های add/clipboard تکراری از صفحات دیگر.
- [x] pass هفتم redesign: کوتاه‌کردن متن کانفیگ‌ها به label کشور/اپراتور/شهر، باریک‌کردن selector Home تا حدود نصف عرض صفحه، و افزودن فاصله امن بالای صفحه زیر status bar گوشی.
- [x] cleanup مرحله ۱: حذف protocol grid و quick actions از Home، تبدیل bottom nav به Home/Locations/Tools، کوتاه‌کردن لیست Locations، و انتقال گزینه‌های فنی به Advanced tools.
- [x] مرحله ۲ polish: تبدیل selector کانفیگ Home و منوی + به bottom sheet حرفه‌ای، افزودن handle/close/action rows، long-press actions برای کانفیگ‌ها، و نمایش compact row در Locations.
- [x] مرحله ۳ Locations: اضافه‌کردن search، گروه‌بندی Favorites / Recently good / All configs، status pill برای Selected/Good/Fav/New، حذف دکمه‌های مدیریت بزرگ، و نگه‌داشتن actions در long-press/bottom sheet.
- [x] مرحله ۴ Settings/Tools: تبدیل تب Tools به Settings ساده، اضافه‌کردن ردیف‌های تنظیمات واقعی، نمایش خلاصه connection status، نگه‌داشتن diagnostics و bootstrap/OpenVPN handoff داخل Advanced tools.
- [x] مرحله ۵ Smart auto test: ذخیره‌شدن وضعیت ON/OFF، تست و رتبه‌بندی saved configs، نمایش last-test در Home/Locations، گروه Recommended، و auto-select بهترین کانفیگ reachable بدون علامت‌زدن آن به‌عنوان verified کامل VPN.
- [x] polish بعد از اسکرین‌شات: safe top viewport برای صفحه‌های scroll شده، اصلاح clipping متن bottom nav، کوتاه‌کردن diagnostics داخل Advanced و انتقال full log به bottom sheet، و تبدیل ابزارهای Advanced به ردیف‌های compact.
- [x] polish اسکرین‌شات دوم: تبدیل Add subscription به bottom sheet هماهنگ با UI، اضافه‌کردن فضای انتهایی برای scroll زیر bottom nav، و pause شدن auto-test/ranking وقتی VPN در حال connect/run است تا تست‌ها از مسیر تونل active گمراه نشوند.
- [x] اصلاح بازخورد بعدی: پشتیبانی subscription از Paste from clipboard، جداکردن صف profileهای هر subscription group در selector/Locations، و hardening سرویس Xray با START_REDELIVER_INTENT و foreground heartbeat و stopWithTask=false برای کاهش احتمال رفتن آیکن VPN در background.
- [x] polish اسکرین‌شات بعدی: نمایش subscription groupها بالای Locations با rowهای compact، اولویت دادن sectionهای subscription قبل از manual configs در selector، و پاکسازی نام‌های provider که به‌صورت `\u....` یا escape خراب نمایش داده می‌شدند.
- [x] بازخورد UI بعدی: حذف کارت Auto test از Home، اضافه‌کردن دکمه شناور تست کنار configها و در search/selector، جداکردن دو مسیر Ping test و Real latency test، و تبدیل subscriptionها به کارت‌های profile/group کنار هم با sheet محدود تا subscriptionهای بزرگ لیست اصلی را پر نکنند.
- [x] hotfix کرش بعد از اضافه‌کردن subscription: حذف Regex مشکل‌دار `\u...` و سپس حذف کامل regex از مسیر cleanProfileLabel تا روی Android PatternSyntaxException ندهد.
- [x] polish تفکیک subscription بعد از بازخورد: تبدیل کارت‌های subscription به tab bar شبیه کلاینت‌های V2Ray با All / Manual / هر subscription profile و نمایش لیست هر گروه به‌صورت جداگانه با شمارنده.
- [x] polish subscription/test بعدی: افزودن refresh all برای همه subscription URLها در Locations، نگه‌داشتن refresh تکی برای گروه انتخاب‌شده، و تغییر semantics تست سریع از سبز/موفق کامل به Ping تا با verified connection اشتباه نشود؛ failure اتصال هم روی profile ذخیره می‌شود.
- [x] polish بازخورد بعدی تست/refresh: انتقال refresh به chip کنار All و کنار هر subscription tab، مستقیم‌کردن دکمه‌های تست لیست/سابسکریپشن به ping-rank، نمایش status تست داخل Locations، و موازی‌کردن ping-rank با سقف ۳۶ config و ۶ worker تا صف‌های بزرگ فقط یکی‌دو مورد را کند و مبهم تست نکنند.
- [x] اصلاح بازخورد بعدی Locations: حذف Manual از شمارنده/tab اصلی وقتی subscription فعال است، مخفی‌کردن profileهای subscription قدیمی/یتیم از All، پاک‌کردن profileهای حذف‌شده از همان group هنگام refresh، و محدودکردن کنترل‌های تست/refresh به chipهای چسبیده به All/subscription tabها.
- [x] ساده‌سازی بیشتر Locations طبق بازخورد: تبدیل search bar به آیکن ذره‌بین collapsed، مخفی‌کردن manual از selector وقتی subscription وجود دارد، حذف راهنمای اضافه زیر tabها، و ساخت کنترل سه‌تکه `◷ / tab / ↻` برای All و هر subscription.
- [x] اصلاح بازخورد مرجع v2rayNG: حذف تست خودکار برای جلوگیری از اسکن subscriptionهای خیلی بزرگ، حذف دکمه‌های test/refresh چسبیده به تک‌تک tabها، و انتقال Test/Refresh به یک Queue tools section تمیز برای صف انتخاب‌شده.
- [x] اصلاح بازخورد بعدی: تبدیل Queue tools به منوی سه‌نقطه شامل Ping test / Real latency / Refresh / Search، کوچک‌کردن header کانفیگ انتخاب‌شده در Locations، و تبدیل Auto ping به Auto latency برای کانفیگ انتخاب‌شده با پیش‌فرض OFF.
- [x] polish اسکرین‌شات بعدی: حذف header برند/selected-config بزرگ از بالای Locations، انتقال نمایش کانفیگ وصل/انتخاب‌شده به یک pill کوچک بالای صفحه با پرچم و عدد تست/latency، و بالا آوردن tabها/Queue tools بعد از آزادشدن فضا.
- [x] polish اسکرین‌شات بعدی: حذف همان بخش‌های خط‌کشی‌شده شامل برند/search/sync status، کارت بزرگ Queue tools و titleهای میانی از Locations، نگه‌داشتن pill کوچک کانفیگ انتخاب‌شده کنار + بالا، انتقال منوی Queue tools به سه‌نقطه کوچک کنار tabها، بالاتر آوردن لیست، lift کردن bottom sheet بالاتر از nav، و تبدیل bottom navigation به glass island شناور.
- [x] polish اسکرین‌شات بعدی: حذف کامل فاصله‌ی Queue tools/Player configs/section label بین tabها و ردیف‌های کانفیگ، تا لیست مستقیم بعد از tabها شروع شود؛ به‌روزرسانی bottom nav با selected pill گرادیانی و elevation بیشتر.
- [x] اصلاح تست latency: گزینه Real latency در Queue tools دیگر VPN را وصل نمی‌کند و به صفحه اصلی نمی‌برد؛ کل صف انتخاب‌شده را به‌صورت سریع/no-VPN و capped تست می‌کند، و عدد ms روی pill جلوی هر کانفیگ نمایش داده می‌شود.
- [x] اصلاح تکمیلی: Auto latency هم دیگر full VPN connect نمی‌کند؛ برای جلوگیری از رفتن ناخواسته به Home/اتصال، تست خودکار selected config به quick no-VPN latency تغییر کرد.
- [x] شروع پنج مرحله بهبود Xray: hardening runtime builder برای REALITY، gRPC، WebSocket/TLS، TCP HTTP header، HTTPUpgrade و XHTTP/SplitHTTP alias با parsing مقاوم‌تر پارامترها، path/host normalization، allowInsecure اختیاری، gRPC authority/multiMode، و unit test برای transport matrix.
- [x] polish subscriptionهای فعلی: چون subscription تست‌شده فعلاً HTTPUpgrade و Reality دارد، برچسب transport غیرمحرمانه روی ردیف‌های Locations/top pill/search و خلاصه import/refresh اضافه شد تا کاربر ببیند هر کانفیگ از Reality یا HTTPUpgrade است بدون نمایش secret/raw link.
- [x] diagnostics مرحله Xray: قبل از start اگر transport/security هنوز map نشده باشد یا REALITY بدون public key باشد، خطای واضح و امن نشان داده می‌شود به‌جای fail مبهم Xray.
- [x] مرحله دوم/سوم Xray polish: نام‌های subscription profile کوتاه‌تر و امن‌تر شدند، labelها از fragment/ps بدون raw secret ساخته می‌شوند، subscriptionهای JSON/YAML-style که داخلشان share-link است بهتر استخراج می‌شوند، و diagnostics حالا warningهای runtime support را نشان می‌دهد.
- [x] مرحله چهارم import UX: ردیف‌هایی که هنوز mapper نیاز دارند badge نارنجی `Map` می‌گیرند، و paste از wrapper URLهای رایج Hiddify/v2rayNG/NekoBox/Clash/Stash که query `url/link/sub/config` دارند به subscription URL واقعی normalize می‌شود.
- [x] مرحله پنجم تمرکز محصول: متن‌های اصلی UI دوباره Xray-first شدند؛ WireGuard و OpenVPN به‌عنوان advanced fallback/handoff توضیح داده می‌شوند نه مسیر اصلی.
- [x] شروع sing-box بعد از Xray: import آزمایشی sing-box JSON اضافه شد؛ outboundهای `vless`, `vmess`, `trojan`, `shadowsocks` endpoint/tag/TLS/REALITY/transport را به‌صورت امن استخراج می‌کنند و در پروفایل جدا ذخیره می‌شوند.
- [x] hardening مرحله sing-box mapper: JSONهای واقعی‌تر با nested `transport.headers.Host`، host/path آرایه‌ای، `grpc` multi mode، `xhttp/split-http` mode، variantهای `serverName/sni` و `reality.publicKey/shortId` بهتر به share-link سازگار با Xray تبدیل می‌شوند.
- [x] شروع Clash/Hiddify/NekoBox-style بعد از sing-box: import آزمایشی Clash/Clash.Meta YAML اضافه شد؛ proxyهای `vless`, `vmess`, `trojan`, `ss/shadowsocks` endpoint/name/TLS/REALITY/network را امن استخراج می‌کنند و profile جدا می‌سازند.
- [x] runtime mapper مرحله اول برای sing-box/Clash: outbound/proxyهای قابل‌تبدیل `vless`, `vmess`, `trojan`, `ss/shadowsocks` به share-link سازگار با Xray synthesize می‌شوند و مسیر Connect برای آن‌ها از embedded Xray استفاده می‌کند؛ transportهای unsupported همچنان ذخیره/diagnostics-only می‌مانند و خطای امن می‌دهند.
- [x] اصلاح Clash subscription URL: فایل‌های YAML مثل `clash.yaml` که به‌جای share-link دارای بخش `proxies:` هستند، هنگام refresh به proxyهای جدا تقسیم می‌شوند و تا سقف امن فعلی در subscription group ذخیره می‌شوند.
- [x] polish بعد از تست گوشی Clash: نام‌های Clash که escape هشت‌رقمی YAML مثل `\U0001F1FA` دارند به emoji/flag واقعی decode می‌شوند، نام subscription از raw GitHub به عنوان قابل‌فهم‌تری مثل `Clash` تبدیل می‌شود، و refresh گروه‌های generic مثل `Raw` هم آن‌ها را rename می‌کند.
- [x] polish بعد از اسکرین‌شات Clash: flag تکراری از title ردیف/top pill حذف شد و تشخیص flag برای کشورهایی مثل کانادا کامل‌تر شد.
- [x] hardening مرحله Clash mapper: YAMLهای پیچیده‌تر با `ws-opts`, `grpc-opts`, `reality-opts`, inline maps، inferred transport، gRPC multi mode، XHTTP/SplitHTTP opts و nested Host/path/public-key بهتر به Xray-compatible share link تبدیل می‌شوند.
- [x] diagnostics مرحله import/mapper: برای Clash و sing-box حالا خطا/هشدار امن و بدون secret نشان می‌دهد مشکل از unsupported type مثل hysteria/tuic/wireguard، transportهایی مثل quic، نبود uuid/password یا REALITY ناقص و missing public-key/pbk است.
- [x] compatibility badge مرحله UI: ردیف‌های Locations/انتخاب profile حالا badge کوتاه و متن امن مثل `Xray`, `Key`, `UDP`, `Engine`, `Handoff` نشان می‌دهند تا کاربر قبل از Connect بفهمد profile آماده Xray است، mapper می‌خواهد، key ندارد یا فقط مسیر handoff/advanced است.
- [x] Runtime details در profile actions: با سه‌نقطه/long-press هر profile، مسیر Connect، دلیل آماده/غیرآماده بودن، endpoint امن، next step و گزینه Real delay برای profileهای آماده به‌صورت on-demand و دقیق دیده می‌شود؛ ردیف‌های لیست برای performance کانفیگ‌های زیاد از badge سبک استفاده می‌کنند و secret/raw config نمایش داده نمی‌شود.
- [x] فیلتر سریع runtime در Locations: از Queue tools می‌توان فقط `Only Xray-ready` یا فقط `Needs attention` را دید تا در subscriptionهای بزرگ بدون test زدن کل صف، کانفیگ‌های آماده یا مشکل‌دار جدا شوند.
- [x] sort سریع Locations: Queue tools حالا مرتب‌سازی `Recommended`, `Newest`, `Latency`, `Runtime-ready first` دارد؛ فقط ترتیب ردیف‌های visible/capped را عوض می‌کند و هیچ test/connect خودکاری اجرا نمی‌کند.
- [x] حفظ کنترل‌شده‌ی نمای Locations: آخرین tab، runtime filter و sort ذخیره می‌شود؛ Search عمداً فقط session-only است تا بعد از بازکردن دوباره اپ لیست پنهان/گیج‌کننده نشود، و از Settings > Subscriptions می‌توان نمای Locations را reset کرد.
- [x] خوانایی بهتر rowهای Locations: عنوان کانفیگ‌ها تا حد ممکن به شکل کوتاه کشور/شهر/اپراتور نمایش داده می‌شود، flag از نام/کد کشور تشخیص داده می‌شود، و جزئیات runtime/latency همچنان در خط دوم یا badge جدا می‌مانند.
- [x] مرحله بعدی مدیریت subscription بزرگ: منوی Queue tools برای subscription فعال گزینه `Load more configs` دارد؛ به‌جای import ناخواسته هزاران کانفیگ، هر بار ۸۰ مورد دیگر تا سقف ایمن ۲۰۰۰ ذخیره می‌شود و refresh همان مقدار load‌شده را حفظ می‌کند.
- [x] رفع ابهام شمارنده subscription بزرگ: tabهای Locations حالا loaded/total را مثل `80/1448` نشان می‌دهند تا مشخص باشد چند کانفیگ واقعاً در subscription هست و چندتا فعلاً داخل اپ load شده.
- [x] UX ورود subscription بزرگ: هنگام اضافه‌کردن URL جدید، اگر تعداد کانفیگ زیاد باشد انتخاب خلاصه نشان داده می‌شود: `Recommended` یا `Custom/All`; تست قبل از ذخیره حذف شد چون تست‌های سالم/واقعی باید بعد از import و از Queue tools/Connect اجرا شوند.
- [x] بازتعریف تست‌ها: Queue tools حالا دو تست قبل اتصال دارد: `Quick check` برای TCP/TLS endpoint reachability و `Real delay` برای اجرای موقت Xray core بدون Android VPN/TUN؛ تست نهایی `Connect & Verify` همچنان هنگام اتصال فقط روی کانفیگ انتخاب‌شده انجام می‌شود.
- [x] رفع کندی Locations برای subscription بزرگ: به‌جای ساختن ۱۳۰۰+ row یک‌باره، ردیف‌ها progressive render می‌شوند (اول ۱۶۰، سپس Show more/long-press all)، درحالی‌که شمارنده loaded/total و Search/Queue tools همچنان کل صف load‌شده را می‌بینند.
- [x] polish رنگ/تم مرحله ۳: مثل reference فقط در هر صفحه یک accent کوچک اضافه شد؛ حلقه دور دکمه power گرادیان خیلی ملایم گرفت، Locations tab/menu accent آبی-یاسی کم‌رنگ دارد، و Settings فقط روی icon chipها رنگ ظریف نشان می‌دهد.
- [x] شروع Settings شبیه v2rayNG ولی ساده‌تر: ردیف‌های Test settings، Subscriptions، Routing & DNS و Diagnostics اضافه شدند؛ Quick check/Real delay limit و Real delay URL قابل تنظیم شدند و safe diagnostics قابل کپی است.
- [x] مرحله بعد Routing & DNS: تنظیم VPN DNS برای Android VPN/Xray core و لیست packageهای bypass برای per-app routing پایه اضافه شد؛ LAN/private IP bypass فعلاً برای Xray مستقیم/ON نگه داشته شد و گزینه‌های Fragment/Mux/FakeDNS به مرحله Advanced موکول شدند.
- [x] مرحله Advanced Xray: کنترل‌های Sniffing، Mux/concurrency و log level به Settings اضافه شدند و واقعاً وارد runtime JSON/Real delay می‌شوند؛ Fragment و FakeDNS فعلاً planned/خاموش ماندند تا بدون نگاشت مطمئن اتصال خراب نشود.
- [x] مرحله verification تکمیلی Xray: هنگام Connect یک HTTP proxy لوکال فقط روی `127.0.0.1` داخل runtime ساخته می‌شود تا بعد از بالا آمدن VPN، public egress IP و مسیر DNS/DoH به‌صورت best-effort از مسیر Xray چک شود؛ جزئیات leak همچنان امن/غیرمحرمانه گزارش می‌شود.
- [x] مرحله lifecycle/reconnect اولیه Xray: سرویس Xray تغییر شبکه Wi‑Fi/mobile/VPN capability را با `ConnectivityManager` می‌گیرد، underlying network را refresh می‌کند، foreground notification را زنده نگه می‌دارد و verification را با debounce دوباره اجرا می‌کند.
- [x] مرحله Smart fallback امن: گزینه Settings با پیش‌فرض OFF اضافه شد؛ اگر کاربر روشن کند و Connect شکست بخورد، اپ فقط تا ۳ کانفیگ نزدیک/هم‌گروه را با backoff کوتاه امتحان می‌کند و queue-wide test یا اسکن هزاران کانفیگ انجام نمی‌دهد.
- [x] polish Settings بعد از اسکرین‌شات: متن بالای Settings کوتاه‌تر شد، ردیف‌های تکراری Add configs/Kill switch حذف شدند، diagnostics حجیم از Advanced panel حذف شد، و ابزارهای کمیاب VPN permission/OpenVPN/bootstrap پشت یک bottom sheet فشرده رفتند تا صفحه اصلی Settings خلوت بماند.
- [ ] طراحی Settings/Drawer و polish گرافیکی بعد از تثبیت dashboard.
- [x] hardening runtime config generator مرحله ۱: XHTTP/SplitHTTP alias، پیام خطای امن‌تر برای UDP/KCP/QUIC، و unit-test matrix برای `tcp/none`, `ws/tls`, `grpc/tls`, `reality`, `httpupgrade`, `xhttp`, `vmess`, `trojan`, `ss` اضافه شد.
- [ ] test matrix گوشی برای `ws/tls`, `tcp/none`, `tcp+http header`, `grpc/tls`, `reality`, `xhttp`, `vmess`, `trojan`, `ss`.
- [ ] DNS leak test کامل‌تر با سرویس authoritative/اختصاصی یا endpoint قابل‌اعتماد، فراتر از check فعلی DoH-through-Xray.
- [ ] reconnect کامل production-grade با restart/backoff سرویس، معیارهای شکست دقیق‌تر، و انتخاب لینک/route بعدی در background؛ نسخه امن UI-driven Smart fallback فعلاً انجام شده است.
- [ ] review نهایی license/notice برای Xray/V2Ray داخلی.
- [ ] تست گوشی با OpenVPN TCP/443 pinned config در یک کلاینت OpenVPN، اگر config سالم پیدا شد.
- [ ] انتخاب license/engine برای OpenVPN داخلی.
- [ ] تشخیص network واقعی و persistent health cache.
- [ ] UI/مستندات Always-on و lockdown/kill switch.

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
- تست گوشی V2Ray نشان داد DoH همچنان reset/timeout یا به `10.10.34.35` poison می‌شود، ولی direct system DNS probe برای endpointهای V2Ray مثل `dns.all.ultradns.space:8880` و hostnameهای Connectoo با TCP موفق شد؛ latency نمونه‌ها از حدود 132ms تا 4163ms بود. بنابراین fallback مستقیم برای diagnostic ارزشمند است. تست start اولیه Xray روی گوشی هنوز آیکن VPN پایدار نشان نداد و status نهایی به `STOPPED / Xray service destroyed` overwrite شد؛ پس UI auto-refresh و حفظ `FAILED` واقعی اضافه شد تا خطای دقیق Android/Xray بعد از start دیده شود. تست بعدی خطای `xray.xudp.basekey` را آشکار کرد و با ساخت base key سی‌ودو بایتی از Android ID رفع شد. تست گوشی بعد از آن آیکن VPN را نشان داد و Xray به `VERIFYING` رسید. پس از افزودن پشتیبانی runtime برای `httpupgrade`/TCP HTTP header و گزارش بهتر verification، تست گوشی `d86ef66` با config کاربر به `VERIFIED` رسید: VLESS `api2.rabbithongo.ir:8880` via `httpupgrade/none`، check به `https://www.gstatic.com/generate_204` چند بار با 126–133ms موفق شد، و stats نمونه‌ها شامل `proxy,downlink,47024;proxy,uplink,27606;` و `proxy,uplink,36929;proxy,downlink,46705;` بودند. مرحله بعدی hardening برای transportهای بیشتری است. اگر آیکن VPN بالای گوشی فعال باشد، نتیجه probe ممکن است از مسیر همین VPN یا VPN خارجی باشد و باید برای reachability خام ایران با VPN خاموش هم تکرار شود.

- پس از status `VERIFIED` روی گوشی، مسیر محصول برای MVP ایران به Xray/V2Ray-first تغییر کرد: import/clipboard config کاربر، حفظ metadata transport، شروع embedded Xray، و verification واقعی. WireGuard برای شبکه‌های غیرایران/UDP-friendly و OpenVPN TCP برای handoff یا engine آینده باقی می‌ماند.
- تصمیم محصولی جدید: اسکلت باید به VPN Hub همه‌کاره/چندموتوره تبدیل شود. یعنی engineها و profileها پشت abstraction مشترک قرار می‌گیرند و UI نهایی فقط یک تجربه ساده Connect/Disconnect نشان می‌دهد؛ diagnostics و جزئیات پروتکل پشت Advanced می‌روند.
- برای V2Ray/Xray نباید IP pinning کور انجام شود؛ فقط وقتی امن است. SNI/Host/path/ALPN/fingerprint/REALITY/httpupgrade باید کامل حفظ شود و در بسیاری از موارد direct Android/system DNS fallback از DoH عملی‌تر است.

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
