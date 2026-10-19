// WO-UI-23 (исходник) / WO-UI-37 (часть B): общие правила отображения
// значений переменных процесса — страница инстанса (root-таблица и
// инспектор) и панель переменных по скоупам. Одна логика в shared/lib,
// не две копии (P-24).
//
// Длинным считается значение длиннее видимой ячейки (порог 120 символов —
// консервативно над ~45 видимыми) или ЛЮБОЕ JSON (даже короткое — его стоит
// показать отформатированным).

export const VALUE_PREVIEW_LIMIT = 120

export function isLongVariableValue(v: { type: string; value: string }): boolean {
  return v.type === 'JSON' || (v.value?.length ?? 0) > VALUE_PREVIEW_LIMIT
}

// JSON — красиво отформатирован, с защитой на не-валидный (fallback — сырая
// строка, не исключение в шаблоне).
export function formatVariableValue(v: { type: string; value: string }): string {
  if (v.type === 'JSON') {
    try {
      return JSON.stringify(JSON.parse(v.value), null, 2)
    } catch {
      return v.value
    }
  }
  return v.value
}
