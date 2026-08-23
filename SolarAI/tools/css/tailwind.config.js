/** @type {import('tailwindcss').Config} */
module.exports = {
  // สแกนคลาสจาก index.html ทั้งไฟล์ (รวม class ที่ JavaScript ใส่ให้ตอน runtime
  // เพราะเขียนเป็น string literal อยู่ในไฟล์เดียวกัน)
  content: ['../../app/src/main/assets/index.html'],
  theme: { extend: {} },
  plugins: [],
}
