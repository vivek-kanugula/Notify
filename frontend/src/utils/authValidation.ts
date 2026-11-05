/**
 * Authentication email validation: enforces Google domain (@gmail.com).
 */
export function isGoogleEmail(val: string): boolean {
  if (!val) return false;
  return /^[a-zA-Z0-9._%+-]+@gmail\.com$/i.test(val.trim());
}
