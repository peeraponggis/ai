# tools/css — ตัวสร้าง Tailwind CSS ที่ฝังในแอป

แอปไม่โหลด Tailwind จาก CDN แล้ว แต่ใช้ไฟล์ CSS ที่ generate ไว้ล่วงหน้าที่
`shared-assets/vendor/tailwind.css` (~11 KB — เล็กกว่า CDN มาก เพราะมีเฉพาะ
class ที่ `index.html` ใช้จริง)

## เมื่อไหร่ต้อง generate ใหม่

**ทุกครั้งที่เพิ่ม Tailwind class ใหม่ใน `index.html`** (class ที่ไม่เคยใช้มาก่อน
จะยังไม่มีใน CSS → ใส่แล้วไม่มีผล) ถ้าแก้แค่ข้อความหรือ logic ไม่ต้อง generate

หมายเหตุ: GitHub Actions รันคำสั่งนี้ให้อัตโนมัติก่อน build APK อยู่แล้ว
ดังนั้น APK ที่ดาวน์โหลดจาก Actions จะถูกต้องเสมอ ขั้นตอนด้านล่างจำเป็นเฉพาะ
ตอน build เองในเครื่อง

## วิธีใช้

```bash
cd SolarAI/tools/css
npm ci        # ครั้งแรกครั้งเดียว
npm run build # generate ../../shared-assets/vendor/tailwind.css
```

ระหว่างแก้ไฟล์ต่อเนื่องใช้ watch mode ได้:

```bash
npx tailwindcss -i input.css -o ../../shared-assets/vendor/tailwind.css --watch
```

## ไฟล์อื่นใน vendor/ (ไม่ต้อง generate)

| ไฟล์ | ที่มา | License |
|---|---|---|
| `vendor/fontawesome/` | Font Awesome Free 6.4.0 (เฉพาะ core + solid + fa-solid-900.woff2) | CC BY 4.0 / SIL OFL 1.1 / MIT — ดู `LICENSE.txt` |
| `vendor/fonts/` | Prompt (400/600/700) + Fira Code จาก Google Fonts, subset latin/latin-ext/thai | SIL OFL 1.1 |

ถ้าต้องใช้ไอคอนตระกูลอื่น (`fa-regular`, `fa-brands`) ต้องก๊อป CSS และ webfont
ของตระกูลนั้นเพิ่มจาก npm package `@fortawesome/fontawesome-free`
