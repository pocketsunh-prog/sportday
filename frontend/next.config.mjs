/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Emits .next/standalone — a self-contained server.js plus only the
  // node_modules it imports. frontend/Dockerfile's runtime stage copies exactly
  // that (with .next/static and public/ beside it) instead of the whole
  // node_modules tree. Harmless for `npm run dev` / `npm start` on the host.
  output: 'standalone',
};

export default nextConfig;
