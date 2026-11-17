import { useEffect, useRef, useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import appCSS from "./App.module.css";
import Navigation from "./Components/Navigation/Navigation";
import Router from "./routes/Routes";
import Login from "./Components/Login/Login";
import { refreshSession, useAuth } from "./config/auth";
import { getPageAccess } from "./config/pageAccess";

function App() {
  const [isGuest, setGuest] = useState(false);
  const [isFeedScrolling, setIsFeedScrolling] = useState(false);
  const pageRef = useRef(null);
  const location = useLocation();
  const auth = useAuth();
  const isHome = location.pathname === "/" || location.pathname === "/home";
  const access = getPageAccess(auth, isGuest, location.pathname);

  useEffect(() => {
    refreshSession().catch(() => {});
  }, []);

  useEffect(() => {
    const page = pageRef.current;
    if (!isHome || !page) return;
    let stopTimer;
    const stop = () => {
      clearTimeout(stopTimer);
      setIsFeedScrolling(false);
    };
    const onScroll = (event) => {
      // Feed scrolls inside the page; ignore scrolling nested comments or dialogs.
      if (event.target !== page && event.target.parentElement !== page) return;
      if (document.hidden || !document.hasFocus()) return;
      setIsFeedScrolling(true);
      clearTimeout(stopTimer);
      stopTimer = setTimeout(stop, 150);
    };
    page.addEventListener("scroll", onScroll, true);
    window.addEventListener("blur", stop);
    document.addEventListener("visibilitychange", stop);
    return () => {
      page.removeEventListener("scroll", onScroll, true);
      window.removeEventListener("blur", stop);
      document.removeEventListener("visibilitychange", stop);
      stop();
    };
  }, [isHome, access]);

  // Nếu đang ở /signin thì chỉ render SignIn
  if (location.pathname === "/signin" || location.pathname.startsWith("/auth/google/")) {
    return (
      <div className={appCSS["signin-page"]}>
        <Router />
      </div>
    );
  }

  //Nếu đang ở /signup thì chỉ render Signup
  if (location.pathname === "/signup") {
    return (
      <div className={appCSS["signup-page"]}>
        <Router />
      </div>
    );
  }

  const isLoggedIn = Boolean(auth.user);
  const tabsDisabled = !isLoggedIn && !isGuest;

  return (
    <div className={appCSS["layout-web"]}>
      <div className={appCSS["nav"]}>
        <Navigation
          feedScrolling={isHome && isFeedScrolling}
          tabsDisabled={tabsDisabled}
          onSignOut={() => setGuest(false)}
        />
      </div>

      <div className={appCSS["pages-container"]}>
        {auth.ready && !auth.error && tabsDisabled && !isHome && (
          <Navigate to="/" replace />
        )}

        {access === "content" && (
          <div className={appCSS["page-slot"]}>
            <div
              ref={pageRef}
              className={appCSS["page-render"]}
            >
              <Router />
            </div>
          </div>
        )}

        {access === "pending" && <p role="status">Checking your session...</p>}
        {access === "error" && (
          <div role="alert">
            {auth.error}
            <button onClick={() => refreshSession().catch(() => {})}>
              Retry
            </button>
          </div>
        )}
        {access === "prompt" && (
          <Login guest={!isGuest ? () => setGuest(true) : undefined} />
        )}
      </div>
    </div>
  );
}

export default App;
