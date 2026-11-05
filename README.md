# Android & My Care — Notification Flow

> "जो मेरे phone व मेरा, दोनों का ख्याल रखे।"

Ek caretaker app jo phone ka bhi khayal rakhta hai aur uske owner ka bhi —
smart, contextual **notification workflows** ke zariye. Notifications me
action buttons, multi-step flows, aur notification ke andar hi input
(WhatsApp-style) — bina kisi heavy UI ke.

**Target device:** Infinix Smart 7 HD (Android 12 Go, 32-bit, 2 GB RAM) —
isi liye APK sirf `armeabi-v7a` ke liye banta hai aur design bilkul halka hai.

## Status: v0.3.0 — Phase 3 COMPLETE (saare 16 workflows LIVE)

Ho gaya hai:
- Android project (Kotlin, package `com.spdev22.mycare`, minSdk 24)
- **App Groups** screen — groups banao, group par tap karke App Picker
  se apps jodo/hato (D1)
- **WorkflowEngine + Pack v3** — Workflow Format v1 ke **16 workflows**
  (`app/src/main/assets/workflows/pack_v1.json`)
- **CareService** — live trigger engine: usage, unlock, battery,
  interval, screenshot/contact observers, naya-app poll-diff,
  cache/unused checks
- **Setup Wizard** — D11 ki saari permissions, ek-ek karke, Hinglish wajah ke saath
- **2.1 jod:** Action-target **App Picker** (Music/Video/Chat buttons ke
  liye app chuno), **Logs/Console** screen, **Signing keystore pin**
  (har build ek hi signature — install-over chalega; v0.3 ke liye ek
  aakhiri uninstall→install lagega)
- **Popup bot type (D7)** — Boredom Dost (4 buttons) aur Naya App→Group
- GitHub Actions se debug APK build

Workflows: Welcome Boss, Boredom Dost, Rest Nudge, Long-App Suggestion,
Study Suggestion, Alarm Suggestion, Aaj Ka Hisaab (D5 7-din pattern ke
saath), Paani Reminder, Battery Full Alarm, Battery Low Warning,
Screenshot Safai, Contact→WhatsApp, Naya App→Group, Cache Safai,
Ghuspaithiya Alert, Unused Apps Safai.

Baaki: sirf Phase 4 — on-device test + jo mile uske fixes.

## Design ke bade faisle

- **D1** Saare usage triggers user ke banaye **App Groups** par bandhe hain,
  fixed app list par nahi — apps badalte rehte hain.
- **D3** Har time/limit ek editable setting hai, sensible default ke saath.
- **D7** Do bot types: **Notification** (max 3 buttons — Android ki limit)
  aur **Popup** (zyada buttons/input ke liye). Carrier (Vi) wale USSD popups
  koi app nahi bhej sakta.
- **D8/D9** Saare workflows ek **fixed format** me data hain, code nahi.
  Ready-made pack aayega; user sirf on/off aur settings badlega.
  Naya pack = chhoti si data file, poora app dobara nahi banana padega.
- **D10** App ki awaaz: Hinglish mix — "आप" wala samman, dostana daant.

## Build

CI har push par debug APK banata hai (Actions tab → artifact `My-Care-APK`).

Local build ke liye pehle wrapper jar restore karo (repo me woh base64
`.b64` file ke roop me rehta hai, kyunki repo text-only channel se
maintain hota hai):

```sh
base64 -d gradle/wrapper/gradle-wrapper.jar.b64 > gradle/wrapper/gradle-wrapper.jar
chmod +x gradlew
./gradlew :app:assembleDebug
```

Launcher icons bhi `app/icon-b64/` me base64 rehte hain; build ke samay
Gradle task unhe PNG mipmaps me decode kar deta hai (SP WEB OS wali trick).

#android_automation #personal_assistant #personal_workflow #care_tacker
#digital_friend #digital_helper
