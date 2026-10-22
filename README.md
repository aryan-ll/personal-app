# Focus Guard

Personal Android app: adult content se door rehne ke liye.

- **Private DNS (Cloudflare Family)** adult sites block karta hai.
- **Guard (Accessibility)** Private DNS, App info/uninstall, Device admin, Accessibility screens ko lock rakhta hai.
- **Device Admin** seedha uninstall rokta hai.
- **Unlock request:** note padhna padega (10 sec), phir 1 ghanta wait, phir 10 minute ke liye sab khulta hai. Phone restart = request cancel.
- **Raat ka strict mode:** 11 PM se 6 AM tak unlock request nahi.
- **VPN loophole band:** VPN settings aur VPN apps ka permission dialog lock (VPN Private DNS ko bypass karta hai).
- **Streak** (kitne din clean), **🆘 SOS** (2 min saans + apna note), **note** ("Shree ji dekh rahi hai !💎", tap karke badlo).

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

## Browser aur Instagram (build 5+)
- **Browser** (Chrome, Brave, Google app, koi bhi): din me kholne pe 5 maths sawaal, sahi hue toh 30 min free. Raat 8 se subah 7 tak band.
- **Instagram:** Stories, Saved reels, DMs (aur DM me aayi reel) chalenge. Home feed scroll, Reels tab, Search/Explore, aur baaki jagah ki reels band. DM reel ke baad aage scroll band.
- Install/update ke baad 15 min TEST mode: Guard log me Instagram screens ke IDs likhe jaate hain ("Log copy karo" se copy).
