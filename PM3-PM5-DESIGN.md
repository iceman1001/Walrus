# Supporting Proxmark3 and Proxmark5 in Walrus

**Status:** design note, not yet implemented.
**Verified against:** `~/pm3/rdv48` (Iceman fork, HEAD `b4c4edd7c`, 2026-08-22).
**Companion doc:** `MODERNIZE-PROMPT.md` (toolchain/AndroidX/API-35 work). This
doc covers only the device-protocol architecture and can be done independently.

---

## TL;DR

1. **The PM5 HAL does not affect Walrus.** It is a firmware compile-time
   abstraction and never crosses the USB boundary.
2. **Do not add a `Proxmark5Device` class.** PM5 is a *capability flag* on the
   same protocol, not a second device type.
3. **Delete the legacy device class outright.** Walrus was written when two
   rival firmware forks existed; only one survives today, so half the device
   code has no target left. This is the single largest maintenance win.
4. **PM5 shares the PM3 USB IDs**, so enumeration already works. Changing that
   upstream is explicitly out of scope for this project.

---

## 1. Why the HAL is a non-issue

`common_arm/Hal.cmake` selects a build platform for the ARM firmware:

| Platform | Description |
|---|---|
| `PM3RDV4` (default) | Proxmark3 RDV4 |
| `PM3GENERIC` | Proxmark3 generic target |
| `PM3ICOPYX` | iCopy-X with XC3S100E |
| `PM5` | Proxmark5 |

Supporting files: `common_arm/Makefile.hal`, `armsrc/Standalone/Makefile.hal`.

The decisive check:

```
$ grep -c hal ~/pm3/rdv48/include/pm3_cmd.h
0
```

The HAL abstracts board-level differences (GPIO maps, FPGA wiring, clocking)
*below* the USB boundary, at firmware compile time. `pm3_cmd.h` — the only file
that defines what goes over the wire — has no knowledge of it. Walrus talks to
the wire. Therefore the HAL is invisible to Walrus and imposes no work.

Corollary: as new platforms are added upstream (more HAL targets), Walrus needs
**no change** unless a new `CMD_*` opcode or capability bit is introduced.

---

## 2. How PM5 actually presents itself

PM5 differs from PM3 in exactly two observable ways.

### 2a. Capability bits

`include/pm3_cmd.h:210-252` — `capabilities_t` already carries PM5 flags
alongside the existing RDV4 ones:

```c
    // rdv4
    bool hw_available_flash : 1;
    bool hw_available_smartcard : 1;
    bool is_rdv4 : 1;

    // pm5
    bool hw_available_fpga_flash : 1;
    bool hw_available_i2c_eeprom : 1;
    bool is_pm5 : 1;
    bool is_pm5_std_ant : 1;
```

Same struct, same command, one query. This is the whole abstraction.

### 2b. Additive opcodes

PM5-specific commands were **added**, not substituted:

| Opcode | Name |
|---|---|
| `0x0177` | `CMD_PM5_QC_TEST` |
| `0x0178` | `CMD_PM5_RGB_SET` (antenna RGB LED) |
| `0x0504` | `CMD_PM5_FPGA_SET_PWR_PWM_LOW_COUNT` |
| ~`0x0632`+ | PM5 dual-frequency antenna control, factory-info EEPROM |

Every command Walrus actually uses — `CMD_LF_HID_WATCH 0x020B`,
`CMD_HF_ISO14443A_READER 0x0385`, `CMD_MEASURE_ANTENNA_TUNING 0x0400`,
`CMD_HF_MIFARE_READBL 0x0620` — is shared across all platforms.

**Consequence:** a PM5 running current firmware will already answer Walrus's
existing command set. PM5 support is not a porting job; it is a detection and
feature-gating job.

### 2c. How PM5 is detected today — and why compile-time is good enough

`is_pm5` is **not** a runtime hardware probe. It is set at compile time
(`armsrc/appmain.c:621-633`):

```c
    capabilities.is_rdv4 = true;      // #ifdef RDV4
    ...
    capabilities.is_pm5 = true;       // #ifdef PM5
    capabilities.is_pm5_std_ant = true;
```

Normally a compile-time flag would be a weak signal — it reports how the
firmware was *built*, not what it is running on. Here it is trustworthy,
because **PM5 is a different MCU entirely**:

| Board | MCU | Core |
|---|---|---|
| PM3 | AT91SAM7S | ARM7TDMI |
| PM5 | AT32F435 | Cortex-M4 |

`include/common.h:109` states it plainly: *"PM5 is at32f435 of CM4 kernal, not
at91sam7s arm7"*. PM3 firmware cannot execute on a PM5 and vice versa, so
"compiled for PM5" and "is a PM5" cannot diverge in practice.

Two further identifiers exist if corroboration is ever wanted:

| Source | Values |
|---|---|
| `CMD_CHIP_TYPE 0x0008` → `main_chip_type_t` | `MAIN_CHIP_TYPE_AT91 = 0` (PM3V), `MAIN_CHIP_TYPE_AT32 = 1` (PM5V) |
| `version_information_t.magic` (`common.h:63-64`) | `0x56334d50` `"PM3V"` / `0x56354d50` `"PM5V"` |

**Recommended detection ladder for Walrus:** use `capabilities.is_pm5` as the
primary signal — it arrives in the handshake Walrus needs anyway (§5) and costs
no extra round trip. Treat `CMD_CHIP_TYPE` as optional corroboration only; note
that support for it is gated by `DEVICE_INFO_FLAG_UNDERSTANDS_CHIP_TYPE (1<<8)`
and is not guaranteed on older firmware. Do not build the detection path on it.

---

## 3. What is wrong with the current design

`app/src/main/java/com/bugfuzz/android/projectwalrus/device/proxmark3/`:

| File | Size | Splits on |
|---|---|---|
| `Proxmark3Device.java` | 19.6K | legacy 544-byte `PacketCommandOLD` framing |
| `Proxmark3DeviceIceman.java` | 20.8K | NG framing |
| `Proxmark3Command.java` | 4.3K | legacy packet |
| `Proxmark3CommandNG.java` | 4.5K | NG packet |

The two device classes are near-identical logic duplicated across two framings,
and both carry the **same** `@UsbCardDevice.UsbIds` annotation — so they cannot
even be distinguished at enumeration time.

**This split is a fossil.** When Walrus was written there were two competing
Proxmark3 firmware forks in wide use — the original official tree (legacy
544-byte `PacketCommandOLD` framing) and the Iceman fork (NG framing). The two
device classes exist to serve one fork each. Today only the Iceman line
survives and is the de-facto standard, so `Proxmark3Device` /
`Proxmark3Command` are maintaining compatibility with a target that no longer
exists.

Note that current *firmware* still accepts OLD-framed packets for backward
compatibility (`armsrc/cmd.c:240-243` falls back to reading a
`PacketCommandOLD` when the NG preamble does not match), so deleting the legacy
Walrus class breaks nothing on the wire. There is simply no longer any reason
to carry a second implementation.

Beyond the dead fork, the split is on the wrong axis anyway. Framing is a
transport detail; what actually varies between real devices is *capabilities*.
Splitting on framing means every protocol fix must be written twice — and
adding PM5 as a third class would make it three times.

---

## 4. Target architecture

```
UsbSerialCardDevice
        |
   Proxmark3Device            <- ONE class, NG framing only
        |
        +-- Pm3Capabilities   <- parsed from CMD_CAPABILITIES at connect
        |     is_pm5, is_rdv4, compiled_with_lf, compiled_with_iso14443a, ...
        |
        +-- feature gates      <- UI and command paths query the record
```

Rules:

- **One device class.** Board identity lives in a data record, never in the
  class hierarchy.
- **Capabilities are read once at connect** and cached for the session.
- **Every optional feature is gated on a flag**, not on a device name string.
- Adding a future platform (PM6, another iCopy variant) means reading one more
  bit — no new class, no new subtype.

### Feature gating map

| Walrus feature | Gate on |
|---|---|
| LF card read/write (HID, Indala) | `compiled_with_lf` |
| HF / MIFARE read | `compiled_with_iso14443a` |
| Antenna tune screen — LF sweep | `compiled_with_lf` |
| Antenna tune screen — HF sweep | `compiled_with_iso14443a` |
| Dual-frequency tune UI | `is_pm5` |
| RGB antenna LED (`CMD_PM5_RGB_SET`) | `is_pm5` |
| Flash/smartcard-dependent features | `hw_available_flash`, `hw_available_smartcard` |
| Buffer sizing for bulk transfers | `bigbuf_size` |

Note `bigbuf_size` and `baudrate` are in the same struct — useful beyond
PM3-vs-PM5 detection, and currently hardcoded or ignored in Walrus.

---

## 5. The capabilities handshake

Walrus **already declares the opcode and never uses it**:

- `Proxmark3Command.java:40` — `static final long CAPABILITIES = 0x0112;`
- `Proxmark3CommandNG.java:40` — `static final short CAPABILITIES = 0x0112;`

A grep for `CAPABILITIES` across `app/src/main/java/` returns those two
declarations and nothing else. The hook is half-built already.

### Reference implementation

`client/src/comms.c:907-920`:

```c
SendCommandNG(CMD_CAPABILITIES, NULL, 0);
if (WaitForResponseTimeoutW(CMD_CAPABILITIES, &resp, 1000, false) == false)
    return PM3_ETIMEOUT;

if ((resp.length != sizeof(g_pm3_capabilities)) ||
    (resp.data.asBytes[0] != CAPABILITIES_VERSION)) {
    // client refuses outright: PM3_EDEVNOTSUPP
}
memcpy(&g_pm3_capabilities, resp.data.asBytes, sizeof(capabilities_t));
```

### Struct layout (`PACKED`, ARM EABI little-endian)

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | `version` — must equal `CAPABILITIES_VERSION` (currently **8**) |
| 1 | 4 | `baudrate` (uint32, LE) |
| 5 | 4 | `bigbuf_size` (uint32, LE) |
| 9 | 4 | 30 packed `bool : 1` bitfields |

Total: **13 bytes**. Bitfield order as declared, starting with `via_fpc`,
`via_usb`, then the `compiled_with_*` block, then RDV4 flags, then the four PM5
flags last.

> **Verify, do not assume, the bit ordering.** Bitfield packing direction in a
> `__attribute__((packed))` struct is ABI-defined. Confirm against a real
> captured response before trusting individual bits. The cheapest check: a
> device with known-set flags (e.g. an RDV4, where `is_rdv4` must read true)
> plus a byte-level dump of the 4 flag bytes. Store that capture as a test
> fixture.

### Version-mismatch policy — differ from the client here

The desktop client hard-refuses on a version mismatch and tells the user to
reflash. That is wrong for a phone app: the user may be in the field with no
way to reflash. Walrus should instead:

1. Accept the connection.
2. Show a clear, dismissible warning naming both versions.
3. Fall back to a **conservative baseline** — assume LF and ISO14443A only, no
   PM5 features, no RDV4 features.
4. Never parse bitfields from an unrecognized struct version.

---

## 6. USB enumeration — resolved, no work needed

Walrus matches these IDs (`Proxmark3Device.java:71-75`, duplicated in
`Proxmark3DeviceIceman.java:72-76`):

| VID | PID | Comment in source |
|---|---|---|
| `0x2d2d` | `0x504d` | CDC Proxmark3 |
| `0x9ac4` | `0x4b8f` | HID Proxmark3 |
| `0x502d` | `0x502d` | Proxmark3 Easy(?) |

These are exactly the three entries in `~/pm3/rdv48/driver/proxmark3.inf`.

**PM5 currently shares these same IDs** — it does not advertise a distinct
VID/PID. So a PM5 already enumerates through Walrus's existing annotation and
**no change is required here.**

Adding a dedicated PM5 VID/PID upstream in the Proxmark repo has been discussed
but is **explicitly out of scope for this project**. Design accordingly:

- Do **not** attempt to identify the board from USB descriptors. It is not
  possible today and would break if IDs change later.
- Identify the board **after** connecting, from `capabilities.is_pm5` (§2c).
- If a distinct PM5 VID/PID does land upstream one day, the only change needed
  here is one more `@UsbIds.Ids` entry — the detection logic is unaffected.
  That is the payoff for keeping identification on the capabilities channel
  rather than on enumeration.

---

## 7. Migration steps

Do these in order; each is independently shippable.

1. **Delete the legacy fork support.** Remove `Proxmark3Device` and
   `Proxmark3Command`; keep `Proxmark3DeviceIceman` + `Proxmark3CommandNG` and
   rename them to drop the now-meaningless `Iceman` suffix — there is only one
   firmware line to be distinguished *from*. Move the surviving class's
   `@UsbIds` annotation across unchanged. Biggest win, and it shrinks every
   later step. ~1 day.
2. ~~**Parse capabilities at connect.**~~ **Done.** `Pm3Capabilities.java`
   parses the struct; `Proxmark3Device.getCapabilities()` queries `0x0112` on
   first use, caches it for the connection, and logs it;
   `Proxmark3Activity` warms the cache once the version fetch has released the
   device lock. 10 JVM unit tests in `Pm3CapabilitiesTest`. No UI surface yet
   and nothing gates on it — that is step 3.
3. **Gate features on flags** per the §4 map, replacing any hardcoded
   assumptions about LF/HF availability. ~half a day.
4. **Add PM5-only extras** (`CMD_PM5_RGB_SET`, dual-frequency tune) behind
   `is_pm5`, only if wanted. Optional.

Step 1 first, deliberately: doing it before step 2 means the capabilities
handshake gets written once instead of twice.

### Payload structs

Most NG opcodes carry a real C struct as their payload, not loose bytes, and
those layouts are the part most likely to drift as the firmware moves. Each one
now has a named Java type beside `Pm3Capabilities` rather than open-coded
`ByteBuffer` arithmetic at the call site:

| Java type | C declaration | Where it is declared |
|---|---|---|
| `Pm3Capabilities` | `capabilities_t` | `include/pm3_cmd.h` |
| `Pm3VersionInfo` | `struct p` in `SendVersion()` | `armsrc/appmain.c` |
| `Pm3AntennaTuning` | `struct p` in `MeasureAntennaTuning()` | `armsrc/appmain.c` |
| `Pm3TuneMode` | (no struct: raw mode/divisor bytes) | `armsrc/appmain.c` |
| `Pm3MfReadBlock` | `mf_readblock_t` | `include/pm3_cmd.h` |
| `Pm3LfHidSim` | `lf_hidsim_t` | `include/pm3_cmd.h` |
| `Pm3Iso14aCardSelect` | `iso14a_card_select_t` | `include/mifare.h` |

`Pm3Structs` holds the shared little-endian reader/writer helpers. Each type
carries its `SIZE`, names the C declaration it mirrors in its javadoc, and
returns null rather than throwing on a short payload. This is also the natural
place for the opcode codegen and captured-frame fixtures to land.

Steps 2 and 3 pair naturally with the opcode-codegen and fixture-replay tests
discussed for `pm3_cmd.h`: capture one real capabilities response per board
type and replay it in JVM unit tests, so board-detection logic is testable
without hardware attached.

---

## 8. What not to build

- **No `Proxmark5Device` class.** One class per board does not scale — the next
  question would be an iCopy-X class, then a PM6 class.
- **No firmware-version string parsing for board detection.** `CMD_VERSION`
  returns human-readable text; `capabilities_t` is the structured, intended
  channel. Use `CMD_VERSION` for display only.
- **No runtime opcode discovery.** The PM3 exposes no opcode registry.
  `capabilities_t` is feature flags, not a command table.
- **No board identification from USB descriptors.** PM3 and PM5 share VID/PID
  today (§6); identify after connecting, from capabilities.
- **No second device class "just in case" a fork reappears.** One firmware
  line exists. If that ever changes, capabilities is where the difference
  should be expressed.
- **No build-time fetch from `~/pm3/rdv48` or upstream.** Vendor a pinned copy
  of `pm3_cmd.h` into this repo and regenerate deliberately, so the build stays
  hermetic and offline. Both projects are GPLv3, so vendoring is licence-clean
  — keep the original copyright header intact.

---

## 9. Open items needing hardware

Two items remain; the rest are now settled.

- [ ] **Capabilities bitfield ordering** on a real device (§5). The parser is
      written and unit-tested against the documented layout, but those tests
      build their own payloads — they cannot confirm the layout is right.
      With a board attached, open the Proxmark3 screen and read:

      ```
      adb logcat -s Proxmark3Device
      ```

      The logged line carries the decoded flags and the raw hex. On an RDV4,
      `is_rdv4` must be set; on any working board, `compiled_with_lf` must be.
      If those read false, the bit order is wrong. Then capture the 13 bytes
      and add them to `Pm3CapabilitiesTest` as a real fixture.
- [ ] **Whether `CMD_LF_HID_WATCH`'s status-only reply behaves identically on
      PM5 and PM3** — see the HID read-path problem in `MODERNIZE-PROMPT.md`.
      Needs one board of each type.

Settled since first draft:

- ~~PM5 USB VID/PID~~ — shares the PM3 IDs; no change needed (§6).
- ~~Whether legacy 544-byte framing is still needed~~ — no. Only one firmware
  line survives, and current firmware still accepts OLD framing anyway, so
  removal is safe (§3).
