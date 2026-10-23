import path from "path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

const repositoryName = process.env.GITHUB_REPOSITORY?.split("/")[1] || "";
const isUserOrOrgPagesRepo = repositoryName.toLowerCase().endsWith(".github.io");
const base = process.env.GITHUB_ACTIONS
  ? (isUserOrOrgPagesRepo ? "/" : `/${repositoryName}/`)
  : "/";

export default defineConfig({
  base,
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
})
