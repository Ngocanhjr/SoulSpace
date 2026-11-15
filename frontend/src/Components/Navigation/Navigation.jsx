import { useState } from "react";
import { Link, NavLink, useNavigate } from "react-router-dom";
import { toast } from "sonner";
import { signOut, useAuth } from "../../config/auth";
import { apiErrorMessage } from "../../config/api";
import { assetUrl } from "../../config/assets";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "../ui/dropdown-menu";
import navcss from "./Navigation.module.css";

export default function Navigation({ feedScrolling = false }) {
  const [signingOut, setSigningOut] = useState(false);
  const { user } = useAuth();
  const navigate = useNavigate();

  const logout = async () => {
    if (signingOut) return;
    setSigningOut(true);
    try {
      await signOut();
      toast.success("Signed out successfully!");
      navigate("/");
    } catch (error) {
      toast.error(apiErrorMessage(error));
    } finally {
      setSigningOut(false);
    }
  };

  return (
    <nav>
      <div className={navcss["layout-navigation"]}>
        <div className={navcss["top-bar"]}>
          <Link to="/">
            <img className={navcss["logo"]} src={assetUrl("logo_nobg.svg")} alt="logo" />
          </Link>
        </div>

        <div className={navcss["center-bar"]}>
          <NavLink
            to="/"
            end
            className={({ isActive }) =>
              `${navcss["iconprivate"]} ${isActive ? navcss["active"] : ""}`
            }
          >
            {({ isActive }) => (
              <img
                src={assetUrl(isActive ? "homeIconActive.svg" : "homeIcon.svg")}
                alt="home"
              />
            )}
          </NavLink>

          <NavLink
            to="/search"
            className={({ isActive }) =>
              `${navcss["iconprivate"]} ${isActive ? navcss["active"] : ""}`
            }
          >
            <img src={assetUrl("search.svg")} alt="search" className={navcss.searchIcon} />
          </NavLink>

          <NavLink
            to="/post"
            className={({ isActive }) =>
              `${navcss["iconprivate"]} ${isActive ? navcss["active"] : ""}`
            }
          >
            {({ isActive }) => (
              <img
                src={assetUrl(isActive ? "plusIconActive.svg" : "plusIcon.svg")}
                alt="plus"
              />
            )}
          </NavLink>

          <NavLink
            to="/user"
            className={({ isActive }) =>
              `${navcss["iconprivate"]} ${isActive ? navcss["active"] : ""}`
            }
          >
            {({ isActive }) => (
              <img
                src={assetUrl(isActive ? "userIconActive.svg" : "userIcon.svg")}
                alt="user"
              />
            )}
          </NavLink>
        </div>

        <div className={navcss["bottom-bar"]}>
          {user && (
            <div className={navcss["session"]}>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    type="button"
                    className={navcss.accountTrigger}
                    title={user.username}
                    aria-label={`Account menu for ${user.username}`}
                    aria-busy={signingOut}
                    disabled={signingOut}
                  >
                    {signingOut ? "…" : `@${user.username}`}
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent side="top" align="start">
                  <DropdownMenuItem onSelect={logout} disabled={signingOut}>
                    Sign out
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          )}
          <div
            className={`${navcss.duck} ${feedScrolling ? navcss.active : ""}`}
          >
            <svg
              viewBox="0 0 36 36"
              role="img"
              aria-label="Vịt nhỏ"
              stroke="#292524"
              strokeWidth="1.7"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path
                d="M25 17c1.8-2 2.6-4 2.6-6.3a8.3 8.3 0 0 0-16.6 0c0 3 1.3 5.2 1 7.5-3.6.5-5.1-1-7.5-3.1C3 20 3.1 24.5 6.2 27.5c2.6 2.5 6.1 3.5 10.1 3.5 7.6 0 12.4-5.7 8.7-14Z"
                fill="#FACC15"
              />
              <path d="M9 23c2.4 3.7 6.4 4.1 9.5.5" fill="#EAB308" />
              <circle cx="22" cy="10" r="1.45" fill="#292524" stroke="none" />
              <path d="m26 12 7.2 2-7.2 3Z" fill="#78350F" stroke="none" />
              <path
                className={navcss.duckBeakUpper}
                d="M26 11.8c2.3-.2 5.6.2 7.4 2.2H26Z"
                fill="#FB923C"
              />
              <path
                className={navcss.duckBeakLower}
                d="M26 14h7.4c-1.5 2.1-4.3 2.7-7.4 1.8Z"
                fill="#F97316"
              />
            </svg>
          </div>
        </div>
      </div>
    </nav>
  );
}
