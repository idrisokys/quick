# Hızlı Paylaş (QuickShare)

[English](README.md)

Yerel ağda çalışan, Google'dan bağımsız basit bir dosya paylaşım uygulaması. Android tablet, Android TV ve Mac arasında bulut, hesap veya PIN olmadan dosya gönderin.

Uygulamalar cihaz diline uyar: Türkçe cihazlarda **Türkçe**, diğer tüm cihazlarda **İngilizce**.

## Özellikler

- Google Play Hizmetleri gerekmez
- Listedeki her yakın cihaza doğrudan gönderilir: cihaza dokunun, dosyaları seçin, bitti
- Ağınızdaki cihazlardan gelen dosyalar otomatik kabul edilir
- Sistemin paylaş menüsünde görünür
- Yerel ağda düz TCP: şifreleme ve el sıkışma yok, aktarım hızlı ve sade

| Klasör | Platform | Not |
|---|---|---|
| [`android/`](android) | Android 10+ (tablet, TV) | Dokunmatik ve kumanda uyumlu, tek ekran |
| [`mac/`](mac) | macOS 13+ | Menü çubuğu uygulaması (300x450 pencere), Dock'ta görünmez, açılışta başlat |

## Kullanım

1. Uygulamayı iki cihaza kurup açın (aynı ağda olun).
2. Listedeki bir cihaza dokunup dosyaları seçin; ya da **Dosya seç**, sürükle-bırak (Mac) veya paylaş menüsünü kullanın.

**Mac kısayolu:** Finder'da dosya veya klasör seçip Finder > Servisler > **QuickShare ile Gönder**'i kullanın. Kısayol atamak için Sistem Ayarları > Klavye > Klavye Kısayolları > Servisler > Dosyalar ve Klasörler bölümünden bu girişe bir kısayol verin. Açık tek cihaz varsa dosyalar sormadan ona gider. Klasörler `.zip` olarak gönderilir.

Alınan dosyalar Android'de ana dizindeki `Link` ("Tüm dosyalara erişim" izni gerekir; izin yoksa kayıt reddedilir), Mac'te `~/Link` klasörüne kaydedilir.

## Derleme

**Android** (JDK 17, Android Studio veya komut satırı):

```
cd android
./gradlew assembleDebug
```

**macOS** (Xcode 15+, [XcodeGen](https://github.com/yonaskolb/XcodeGen)):

```
cd mac
brew install xcodegen
xcodegen generate
open QuickShare.xcodeproj
```

Paylaş menüsü eklentisini *Sistem Ayarları → Eklentiler → Paylaş menüsü* altında etkinleştirin. İndirilen sürümü macOS engellerse: `xattr -dr com.apple.quarantine /Applications/QuickShare.app`.

GitHub Actions her push'ta iki uygulamayı da derler. `v1.0` gibi bir etiket gönderirseniz APK ve Mac uygulaması sürüme eklenir.

## Nasıl çalışır

- Keşif: `_qshare._tcp` mDNS servisi, TXT kayıtları `n` (ad) ve `i` (rastgele cihaz kimliği)
- Aktarım: düz TCP, dosya başına tek `POST /u` isteği (ad `X-Name` başlığında)

## Güvenlik notu

Aktarım şifreli değildir ve aynı ağdaki her cihaz size dosya gönderebilir; dosyalar sorulmadan kaydedilir. Yalnızca ev ağı gibi güvendiğiniz ağlarda kullanın.
