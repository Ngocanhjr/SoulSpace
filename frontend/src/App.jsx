import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import appCSS from "./App.module.css";
import Navigation from "./Components/Navigation/Navigation";
import Router from "./routes/Routes";
import Login from "./Components/Login/Login";
import { refreshSession, useAuth } from "./config/auth";

function App() {
  const [isGuest, setGuest] = useState(false);
  const [isFeedScrolling, setIsFeedScrolling] = useState(false);
  const pageRef = useRef(null);
  const location = useLocation();
  const navigate = useNavigate();
  const auth = useAuth();
  const isHome = location.pathname === "/" || location.pathname === "/home";

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
  }, [isHome]);

  // Nếu đang ở /signin thì chỉ render SignIn
  if (location.pathname === "/signin") {
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
  const isPostPage = location.pathname === "/post";
  const requiresLogin = isPostPage || location.pathname === "/user";
  const showLogin = requiresLogin && auth.ready && !auth.error && !isLoggedIn;

  return (
    <div className={appCSS["layout-web"]}>
      <div className={appCSS["nav"]}>
        <Navigation feedScrolling={isHome && isFeedScrolling} />
      </div>

      <div className={appCSS["pages-container"]}>
        {!showLogin && (
          <div className={appCSS["page-slot"]}>
            <div
              ref={pageRef}
              className={`${appCSS["page-render"]} ${requiresLogin && !isLoggedIn ? appCSS["post-state"] : ""}`}
            >
              <Router />
            </div>
          </div>
        )}

        {auth.error && !isPostPage && (
          <div role="alert">
            {auth.error}
            <button onClick={() => refreshSession().catch(() => {})}>
              Retry
            </button>
          </div>
        )}
        {auth.ready &&
          !auth.error &&
          !isLoggedIn &&
          (!isGuest || requiresLogin) && (
            <Login
              guest={(value) => {
                setGuest(value);
                if (requiresLogin) navigate("/");
              }}
            />
          )}
      </div>
    </div>
  );
}

export default App;
