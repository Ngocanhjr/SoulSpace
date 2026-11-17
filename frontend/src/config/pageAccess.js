export function getPageAccess(auth, isGuest, pathname) {
  if (!auth.ready) return "pending";
  if (auth.error) return "error";
  if (auth.user) return "content";
  const isHome = pathname === "/" || pathname === "/home";
  return isGuest && isHome ? "content" : "prompt";
}
