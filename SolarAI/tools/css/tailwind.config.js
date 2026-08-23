/** @type {import('tailwindcss').Config} */
module.exports = {
  // สแกนคลาสจาก index.html ทั้งไฟล์ (รวม class ที่ JavaScript ใส่ให้ตอน runtime
  // เพราะเขียนเป็น string literal อยู่ในไฟล์เดียวกัน)
  content: ['../../app/src/main/assets/index.html'],

  // คลาสที่ JS ประกอบชื่อขึ้นตอนรัน เช่น `text-${c}-400` — ตัวสแกนหาไม่เจอ
  // ต้องประกาศไว้ตรงนี้ ไม่งั้นการ์ดความเสี่ยง/ทางเลือกจะไม่มีสี
  safelist: [
    'text-rose-400', 'text-amber-400', 'text-emerald-400', 'text-cyan-400',
    'text-purple-400', 'text-slate-400',
    'bg-rose-900/40', 'bg-amber-900/40', 'bg-emerald-900/40',
    'border-rose-800', 'border-amber-800', 'border-emerald-800',
  ],
  theme: { extend: {} },
  plugins: [],
}
