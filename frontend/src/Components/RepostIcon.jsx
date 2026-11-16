import { Repeat2 } from "lucide-react";

export default function RepostIcon({ checked, ...props }) {
  if (!checked) return <Repeat2 {...props} />;

  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" {...props}>
      <path d="M3 13V9a3 3 0 0 1 3-3h9m-4-4 4 4-4 4" />
      <path d="M21 11v4a3 3 0 0 1-3 3H9m4 4-4-4 4-4" />
      <path d="m8 12 2 3 6-5" />
    </svg>
  );
}
