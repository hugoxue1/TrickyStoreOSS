# Tricky Store OSS

*A trick of Keystore they forgot to hide.*

A fully open-source, FOSS alternative to the proprietary [TrickyStore](https://github.com/5ec1cff/TrickyStore) Magisk module.

---

## Why this exists

TrickyStore's author has a track record of [violations and questionable practices](docs/5ec1cff-violations.md)

So this is a complete rewrite from scratch, built on:

- The projects credited in [Acknowledgements](#acknowledgements)
- Official changelogs and the expected behavior of newer releases
- Original fixes and features carried over from an earlier fork of the old codebase

Licensed under **GPLv3** and it stays that way.

---

## Features

- 100% FOSS, no closed-source components
- Matches the proprietary implementation's behavior and feature set as closely as possible

## Requirements

- Android 10+

---

## Installation

1. Flash the module and reboot
2. *(Optional)* Place an unrevoked hardware keybox at `/data/adb/tricky_store/keybox.xml` for extended integrity
3. *(Optional)* Customize target packages in `/data/adb/tricky_store/target.txt`
4. *(Optional)* Customize the security patch level in `/data/adb/tricky_store/security_patch.txt`

All config files take effect immediately — no reboot needed after step 1.

---

## Configuration

### `keybox.xml`

```xml
<?xml version="1.0"?>
<AndroidAttestation>
    <NumberOfKeyboxes>1</NumberOfKeyboxes>
    <Keybox DeviceID="...">
        <Key algorithm="ecdsa|rsa">
            <PrivateKey format="pem">
-----BEGIN EC PRIVATE KEY-----
...
-----END EC PRIVATE KEY-----
            </PrivateKey>
            <CertificateChain>
                <NumberOfCertificates>...</NumberOfCertificates>
                <Certificate format="pem">
-----BEGIN CERTIFICATE-----
...
-----END CERTIFICATE-----
                </Certificate>
                <!-- more certificates -->
            </CertificateChain>
        </Key>
    </Keybox>
</AndroidAttestation>
```

### `target.txt` — mode selection

Tricky Store OSS supports two modes: **leaf certificate hacking** and **certificate generation**. On TEE-broken devices, leaf hacking won't work since the leaf certificate can't be retrieved from TEE. The module picks the right mode automatically per device.

Override per package with a suffix:

| Suffix | Behavior |
|--------|----------|
| *(none)* | Automatic mode |
| `?` | Force leaf hacking |
| `!` | Force certificate generation |

```
# target.txt
com.google.android.gsf              # automatic
io.github.vvb2060.keyattestation?   # leaf hacking
com.google.android.gms!             # certificate generation
```

### `security_patch.txt`

Optional. Lives at `/data/adb/tricky_store/security_patch.txt`. It sets the three patch levels a spoofed attestation reports: `osPatchLevel` (system), `vendorPatchLevel`, and `bootPatchLevel`. It only changes KeyAttestation output, not system properties. Changes apply on save, so no reboot is needed.

Lines starting with `#` are comments, and blank lines are ignored.

#### Global and per-package

Settings above a package header are considered global and is the default for every app. A package header (`[package.name]`) on its own line targets one app; everything below it applies only to that app until the next header. This lets you give different apps different dates, for example an old system date for `com.google.android.gms` and a recent one everywhere else.

A package inherits anything it does not set from the global block. For example: `[com.google.android.gms]` block that sets only `system=` still picks up the global `vendor=` and `boot=`.

#### Keys and dates

The keys are `system`, `vendor`, `boot`, and `all`. `all` sets all three at once and any single key overrides it.

Dates can be written as `YYYY-MM-DD`, `YYYYMMDD`, or `YYYYMM`. `YYYY`, `MM`, and `DD` work as placeholders for the current year, month, and day; they resolve on every attestation, so `YYYY-MM-05` always lands on the 5th of the current month.

#### Special keywords

- `no` omits that patch level tag entirely. The attestation reports nothing for it.
- `device_default` keeps the device's real value for that component.
- `prop` mirrors the system security-patch prop (`ro.build.version.security_patch`). It is kept for backward compatibility; `device_default` is the more accurate name for new configs.

#### Examples

Simple form, one date for all three levels:

```
20241101
```

Per partition:

```
# system patch level
system=202411
# report nothing for boot
boot=no
# vendor, alternate date format
vendor=2024-11-01
# keep the device's real boot level instead
# boot=device_default
```

Per-package overrides:

```
# global default for every app
system=YYYY-MM-05
vendor=device_default
boot=no

# GMS needs the old print date for a legacy <A13 STRONG verdict
[com.google.android.gms]
system=2024-10-01

# a demo app with its own set
[org.app.demo]
all=2025-09-15
boot=device_default
```

GMS overrides only `system`; it inherits `vendor=device_default` and `boot=no` from the global block. The demo app sets all three to `2025-09-15` via `all`, then carves boot back out to the real device value.

> This only affects KeyAttestation results. `resetprop` can be used separately if you need to change system properties.

---

## Contributing

PRs welcome. Thanks for backing real open-source work.

## Acknowledgements

- [BootloaderSpoofer](https://github.com/chiteroman/BootloaderSpoofer) *(dead, relies on forks/mirrors)*
- [FrameworkPatch](https://github.com/chiteroman/FrameworkPatch) *(dead, relies on forks/mirrors)*
- [KeyAttestation](https://github.com/vvb2060/KeyAttestation)
- [KeystoreInjection](https://github.com/aviraxp/Zygisk-KeystoreInjection)
- [PLTI](https://github.com/PerformanC/PLTI)
- [LSPosed](https://github.com/LSPosed/LSPosed)
- [PlayIntegrityFork](https://github.com/osm0sis/PlayIntegrityFork)

## ⚠️ keybox.xml 部署警示（2026-11-13 A13 事故，勿重蹈）

本钥匙箱 = 旧软件系谱版（EC 叶子 2026-01-08 过期）——**勿换成 TEE 系谱钥匙箱**：
Momo v4.4.1 的 TEE 校验认得 TEE 系谱链（title=TEE serial=97523f88 系），换装后
momo 报 "TEE 损坏"；本旧软件箱 momo/Hunter 均不识别（实测通过）。过期对
momo/Hunter 的 TEE 校验无影响（两家均不深验有效期）。

**结构纪律（2026-11-13 A13 事故教训，100 号档 §十三）**：
- `module/keybox.xml` 的 `<?xml?>` 声明必须在**文件第 1 行第 1 字节**——
  声明前不能有任何字节（包括注释/空行/BOM）。a41a99c 曾把部署警示注释拼在
  声明之前 → TS 解析必失败（XmlPullParserException）→ 拦截退化 → Hunter
  三卡报险（属性/证书矛盾）。已修复（0aadaaf 前身）。
- **打包/部署后必须跑解析器校验**：`python -c "import
  xml.etree.ElementTree as ET; ET.parse('module/keybox.xml')"`。
