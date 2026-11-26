import { StrictMode } from "react"
import { createRoot } from "react-dom/client"
import "./index.css"
import App from "./App.tsx"
import { MockModeBanner } from "@/components/common/mode-banner"

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <MockModeBanner />
    <App />
  </StrictMode>,
)
