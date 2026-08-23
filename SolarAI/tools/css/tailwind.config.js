/** @type {import('tailwindcss').Config} */
module.exports = {
  // สแกนคลาสจาก index.html ของทุกโมดูล (รวม class ที่ JavaScript ใส่ให้ตอน runtime
  // เพราะเขียนเป็น string literal อยู่ในไฟล์เดียวกัน)
  // CSS ที่ได้ใช้ร่วมกันทั้งสองแอปผ่าน shared-assets/vendor/tailwind.css
  content: [
    '../../app/src/main/assets/index.html',
    '../../voicetranslate/src/main/assets/index.html',
  ],
  theme: { extend: {} },
  plugins: [],
}
