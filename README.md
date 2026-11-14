# الفاتح الآمن · SafeOpen

تطبيق أندرويد بفتح **كل أنواع الملفات** (وورد، إكسل، باوربوينت، PDF، صور، فيديو، ZIP…) وبفحص كل ملف **قبل ما يفتحه** إذا فيه اختراق أو لا.

An Android app that opens almost any file — Word, Excel, PowerPoint, PDF, images, video, ZIP — and **scans every file for malware techniques before opening it**.

---

## التثبيت · Install

1. نزّل ملف `SafeOpen.apk` من صفحة **Releases** (أو من **Actions ← آخر بناء ← Artifacts**).
2. افتحه على التلفون، ووافق على "التثبيت من مصادر غير معروفة" لهاد المرة.
3. افتح أي ملف من التطبيق، أو من أي تطبيق ثاني عن طريق **فتح باستخدام ← الفاتح الآمن**.

Download `SafeOpen.apk` from **Releases** (or the latest **Actions** run → *Artifacts*), open it on the phone and allow installing from this source. Android 8.0+ is required.

## شو بفتح · What it opens

| النوع | الصيغ |
|---|---|
| وورد | DOCX, DOCM, DOTX, DOC*, RTF, ODT |
| إكسل | XLSX, XLSM, XLS*, CSV, TSV, ODS |
| باوربوينت | PPTX, PPTM, PPSX, PPT*, ODP |
| مستندات | PDF, EPUB, HTML, TXT, JSON, XML, Markdown, أي ملف نصي |
| صور | JPG, PNG, GIF, WEBP, HEIC, AVIF, BMP, SVG |
| فيديو وصوت | MP4, MKV, WEBM, 3GP, MP3, M4A, WAV, OGG, FLAC, AAC |
| مضغوطة | ZIP, APK, JAR (تتصفح الملفات اللي جواها وتفتحها، وكل وحدة بتنفحص) |
| أي شي ثاني | عرض خام كنص أو Hex |

\* صيغ أوفيس القديمة (97–2003) بنعرض النص اللي فيها.

**المجلدات:** "افتح مجلد" بتختار أي مجلد وبتتصفحه، و"تصفّح كل ذاكرة التلفون" بتوصل لكل المجلدات (بطلب إذن الوصول لكل الملفات). وزر 🔍 **افحص المجلد** بفحص كل الملفات اللي بالمجلد واللي جوا مجلداته الفرعية.

## الفحص الأمني · Security scanner

بشتغل على التلفون نفسه بدون إنترنت، وبحدد النوع **الحقيقي** للملف من محتواه مش من اسمه، وبعدين بدوّر على:

- **أوفيس:** ماكرو VBA، ماكرو بشتغل تلقائياً (AutoOpen/Document_Open…)، استدعاءات خطيرة (Shell، PowerShell، URLDownloadToFile…)، ماكرو Excel 4.0 (XLM)، ActiveX، كائنات OLE مضمّنة، برامج مخبّاة جوا المستند، **حقن القوالب عن بُعد**، **Follina (CVE-2022-30190)**، حقول **DDE**، ثغرة **محرر المعادلات (CVE-2017-11882)**، كائنات RTF ذاتية التحديث، ماكرو LibreOffice.
- **PDF:** جافاسكربت، أوامر تلقائية (OpenAction/AA)، Launch، ملفات مرفقة (خصوصاً البرامج)، RichMedia/Flash، XFA، **كلمات مموّهة** (`/J#61vaScript`)، وكمان بفك الـ streams المضغوطة عشان يفحص اللي مخبّى فيها.
- **التنكّر:** برنامج متخفي بامتداد مستند أو صورة، امتداد مزدوج (`photo.jpg.exe`)، حرف عكس الاتجاه RTLO بالاسم، برامج ويندوز/لينكس/ماك/أندرويد، سكربتات (PS1/VBS/BAT/JS…) وأوامر مشبوهة جواها.
- **الأرشيفات:** قنابل ZIP، Path Traversal (`../`)، ملفات مشفّرة، برامج مخبّاة جوا الأرشيف.
- **الويب:** صفحات تصيّد فيها نموذج كلمة سر، SVG فيها سكربت.
- بصمة SHA-256 لكل ملف، وكشف ملف الاختبار EICAR.

**النتيجة:** ✅ آمن ← بنفتح مباشرة · ⚠️ مشبوه ← بتختار تفتحه بالوضع المحمي · ⛔ خطير ← بنحظره (وبتقدر تشوف المحتوى الخام بأمان).

> الفحص ذكي بالقواعد ومش بديل لبرنامج مكافحة فيروسات كامل بتحديثات توقيعات. بس حتى لو ملف عدّى من الفحص، **التطبيق ما بنفّذ أي شي من جواه**.

## الحماية من الاختراق · Hardening

- **ما في صلاحية INTERNET:** مستحيل أي ملف أو أي كود يبعت بياناتك لبرا التلفون.
- **ما في تنفيذ لأي كود:** المستندات بتتحول لـ HTML ثابت، بدون JavaScript وبدون شبكة، ومعه Content-Security-Policy صارمة (`default-src 'none'`). الـ PDF بترسمه مكتبة أندرويد اللي ما فيها محرك جافاسكربت.
- WebView مقفول: JS مطفي، ما في وصول لملفات أو content، كل الطلبات الخارجية محظورة، والتنقّل ممنوع.
- قارئ XML بدون معالجة DTD، يعني محمي من XXE ومن تضخيم الكيانات.
- حدود للحجم والذاكرة بكل مكان (قنابل ZIP، جداول ضخمة، صور ضخمة).
- مكوّن واحد بس مفتوح للتطبيقات الثانية (`OpenFileActivity`)، وهو بفحص الرابط وبرفض أي محاولة تخلّيه يقرأ ملفاته الخاصة (`file:///data/data/...`). وشلنا كمان كل المكوّنات المفتوحة اللي بتجيبها المكتبات.
- الملفات بتنفتح كنسخة بمساحة التطبيق الخاصة، وبتنمسح أول ما تسكّر. وأسماء الملفات جوا الأرشيفات ما بتُستخدم أبداً كمسارات.
- `allowBackup=false`، ما في نسخ احتياطي سحابي، وفي خيار **منع لقطات الشاشة**.
- كود الإصدار مصغّر ومموّه بـ R8.

## البناء من المصدر · Build from source

Requirements: JDK 17+, Android SDK (platform 35, build-tools 35).

```bash
./gradlew testDebugUnitTest   # 31 unit tests (scanner + renderers)
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Release signing reads `keystore.properties` (not committed):

```properties
storeFile=../signing/safeopen-release.jks
storePassword=...
keyAlias=safeopen
keyPassword=...
```

Without it, release builds are signed with the debug key. On GitHub, add the secrets `SAFEOPEN_KEYSTORE_BASE64`, `SAFEOPEN_KEYSTORE_PASSWORD`, `SAFEOPEN_KEY_ALIAS` and `SAFEOPEN_KEY_PASSWORD` to make CI sign with your own key. Pushing a tag such as `v1.0.0` publishes the APK as a GitHub Release.

## Project layout

```
app/src/main/java/com/safeopen/viewer/
  security/   Scanner (threat detection), ZipInspector (raw ZIP central directory)
  render/     DOCX / XLSX / PPTX / ODF / EPUB / text / RTF / CSV → inert HTML
  ui/         Main, Folder browser + folder scan, Viewer, PDF pages, zoom
  storage/    Folder sources (system picker or full storage)
  util/       Safe file copy + URI validation, byte helpers
```
