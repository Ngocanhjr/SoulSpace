import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
} from "@dnd-kit/core";
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  horizontalListSortingStrategy,
} from "@dnd-kit/sortable";
import { SortableItem } from "./Components/SortableItem";
import appCSS from "./App.module.css";
import Navigation from "./Components/Navigation/Navigation";
import Router from "./routes/Routes";
import Login from "./Components/Login/Login";
import Explore from "./page/ExplorePage/Explore";
import { refreshSession, useAuth } from "./config/auth";

function App() {
  const [showSecond, setShowSecond] = useState(false);
  const [isGuest, setGuest] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  const auth = useAuth();

  useEffect(() => {
    refreshSession().catch(() => {});
  }, []);

  const [items, setItems] = useState(["page", "explore"]);

  const sensors = useSensors(
    useSensor(PointerSensor),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  const handleDragEnd = (event) => {
    const { active, over } = event;
    if (!over || active.id === over.id) return;

    setItems((items) => {
      const oldIndex = items.indexOf(active.id);
      const newIndex = items.indexOf(over.id);
      return arrayMove(items, oldIndex, newIndex);
    });
  };

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
        <Navigation showPage={() => setShowSecond(!showSecond)} />
      </div>

      <DndContext
        sensors={sensors}
        collisionDetection={closestCenter}
        onDragEnd={handleDragEnd}
      >
        <SortableContext items={items} strategy={horizontalListSortingStrategy}>
          <div
            className={`${appCSS["pages-container"]} ${
              showSecond ? appCSS["two"] : appCSS["one"]
            }`}
          >
            {items.map((id) =>
              id === "page"
                ? !showLogin && (
                    <SortableItem key="page" id="page">
                      <div
                        className={`${appCSS["page-render"]} ${requiresLogin && !isLoggedIn ? appCSS["post-state"] : ""}`}
                      >
                        <Router />
                      </div>
                    </SortableItem>
                  )
                : showSecond && (
                    <SortableItem key="explore" id="explore">
                      <div className={appCSS["box-explore"]}>
                        <Explore />
                      </div>
                    </SortableItem>
                  ),
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
        </SortableContext>
      </DndContext>
    </div>
  );
}

export default App;
