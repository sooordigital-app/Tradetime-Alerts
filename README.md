# TradeTime Alerts — Android APK Setup (GitHub Actions)

Ye folder aapke `TradeTime-Alerts.html` app ko ek Android APK (Capacitor
wrapper) mein badalne ke liye tayyar setup hai, jisme phone ka apna
Alarm/Ringtone/Notification tone select kar ke alert par bajaya ja sakta
hai.

**Zaroori baat:** Android project (`android/` folder) sirf Capacitor CLI
generate kar sakti hai, aur uske liye ek dafa Node.js/npm chalana zaroori
hai — ye step aapko apne PC par (ya Android Studio/Termux mein) khud karna
hoga, kyunke ye sandboxed environment internet access nahi rakhta jahan
main ye command chala sakoon. Uske baad, har future build GitHub Actions
khud-ba-khud kar dega — aapko dobara kuch manually karne ki zaroorat nahi.

---

## Step 1 — Ek dafa apne PC par (Node.js chahiye)

1. [Node.js](https://nodejs.org) install karein (agar pehle se nahi hai).
2. Is poore folder (`tradetime-apk-setup`) ko apne PC par download/copy
   karein, aur usi folder ke andar terminal khol kar:

   ```bash
   npm install
   npx cap add android
   ```

   Ye command ek `android/` folder generate kar degi jisme poora native
   Android project hoga.

3. Ab custom plugin files ko generated project mein copy karein:

   ```bash
   # Plugin ka Java file:
   cp native-plugin/AlarmSoundPlugin.java \
      android/app/src/main/java/com/tradetime/alerts/AlarmSoundPlugin.java

   # MainActivity ko replace karein (plugin register karne ke liye):
   cp native-plugin/MainActivity.java \
      android/app/src/main/java/com/tradetime/alerts/MainActivity.java
   ```

   (Agar `com/tradetime/alerts` folder maujood na ho to `capacitor.config.json`
   mein diya gaya `appId` check karein — path usi ke mutabiq hoga.)

4. Ek dafa local test build kar ke dekh lein (optional, Android SDK chahiye
   hoga):

   ```bash
   npx cap sync android
   cd android && ./gradlew assembleDebug
   ```

   APK yahan banega: `android/app/build/outputs/apk/debug/app-debug.apk`

5. Sab kuch (including `android/` folder) GitHub repo mein push kar dein:

   ```bash
   git init
   git add .
   git commit -m "Capacitor Android wrapper + alarm tone plugin"
   git branch -M main
   git remote add origin https://github.com/<aapka-username>/<repo-name>.git
   git push -u origin main
   ```

---

## Step 2 — Uske baad: GitHub Actions khud APK banayega

`.github/workflows/build-apk.yml` already is folder mein maujood hai. Jab
aap `main` branch par push karenge (ya GitHub ke "Actions" tab mein
"Run workflow" dabayenge), GitHub khud:

1. Node + Java install karega
2. `npm install` + `npx cap sync android` chalayega (jo `www/index.html`
   ko android project mein copy kar deta hai)
3. `./gradlew assembleDebug` se APK build karega
4. APK ko **Artifacts** ke tor par upload kar dega

Build khatam hone ke baad:
**Repo → Actions tab → us workflow run ko open karein → neeche
"Artifacts" section mein `TradeTime-Alerts-debug-apk.zip` download
karein.** Usme aapki APK hogi — phone mein transfer kar ke install kar
lein (Unknown Sources allow karna parega, kyunke ye Play Store se nahi
aa rahi).

---

## Phone ka Alarm Tone select karna

App ke andar header mein ek button hai: **"🔔 Set Phone Alarm Tone"**.

- Ye button sirf installed Android app ke andar kaam karta hai (browser
  mein khud-ba-khud chhup jata hai, kyunke browser mein phone ke tones
  tak access nahi hota).
- Tap karne par Android ka apna tone-picker khulega — jisme aapke phone
  ke saare Alarm, Ringtone aur Notification tones dikhenge.
- Jo tone select karenge, wahi ab har EMA/candle-cross alert par bajega
  (pehle wala simple "beep" sirf browser-testing ke liye fallback hai).

---

## Limitation jo jaan lena zaroori hai

Ye ek WebView-based app hai — matlab jab tak app **open ya background
mein memory mein zinda** hai, tab hi alerts check/bajenge, bilkul waise
hi jaise browser tab khula rehne par kaam karta tha. Agar Android app
ko poori tarah **swipe kar ke band** kar de ya phone bahut der tak
"deep sleep/Doze" mode mein rahe, to timers ruk sakte hain — ye har
WebView-wrapped app ki common limitation hai.

Agar aap chahein to iska agla step ek **foreground service +
WorkManager** add karna hoga, jo Android ko batata hai "ye app
background mein active kaam kar rahi hai, ise mat rokna" — is se alerts
app band hone ke baad bhi zyada reliably chalte rahenge. Ye thoda
bada native-code addition hai; bataiye agar ye bhi chahiye, main agla
step bana deta hoon.
