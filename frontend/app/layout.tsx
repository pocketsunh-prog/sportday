import type { Metadata } from 'next';
import './globals.css';
import { AuthProvider } from '@/lib/auth';
import { Navbar } from '@/components/Navbar';

export const metadata: Metadata = {
  title: 'SportDay - Sports Event Management',
  description: 'Manage sports events, enrollments, and results',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <AuthProvider>
          <Navbar />
          <main className="container" style={{ paddingTop: '2rem', paddingBottom: '2rem' }}>
            {children}
          </main>
        </AuthProvider>
      </body>
    </html>
  );
}
