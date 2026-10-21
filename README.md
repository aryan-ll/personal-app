# Focus Guard

Personal Android app: adult content se door rehne ke liye.

- **Private DNS (Cloudflare Family)** adult sites block karta hai.
- **Guard (Accessibility)** Private DNS, App info/uninstall, Device admin, Accessibility screens ko lock rakhta hai.
- **Device Admin** seedha uninstall rokta hai.
- **Unlock request:** 1 ghanta wait, phir 10 minute ke liye sab khulta hai. Phone restart = request cancel.

## APK kaise milega
Har push pe GitHub Actions APK banata hai → repo ke **Releases** me `focus-guard.apk`.

## Setup (isi order me)
0. Install ke baad: Settings → Apps → Focus Guard → ⋮ → "Allow restricted settings" (Android 13+).
1. App me "Private DNS set karo" → `family.cloudflare-dns.com` paste.
2. "Device Admin on karo".
3. "Accessibility me Focus Guard on karo" (aakhri, kyunki iske baad lock chalu).

## Dhyan rahe
- Lock ke time Network/Accessibility settings page bhi band rahenge (Wi-Fi quick settings tile se chalega).
- Ye speed-breaker hai, deewar nahi: Safe Mode / PC (ADB) se hat sakta hai.
