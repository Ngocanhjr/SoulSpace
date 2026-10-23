import { useState } from "react";
import { Link, NavLink } from "react-router-dom";
import { assetUrl } from "../../config/assets";
import navcss from "./Navigation.module.css";

export default function Navigation({ showPage }) {
  const [compassActive, setCPActive] = useState(false);

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
          <div
            className={`${navcss["bar"]} ${
              compassActive === true ? navcss["active"] : ""
            }`}
            onClick={() => {
              setCPActive(!compassActive);
              showPage?.();
            }}
          >
            <img src={assetUrl("compass-regular-full.svg")} alt="explore" />
          </div>
        </div>
      </div>
    </nav>
  );
}
