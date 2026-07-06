'use client';

import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { api, AuthResponse } from './api';

interface AuthContextType {
  user: AuthResponse | null;
  login: (username: string, password: string) => Promise<void>;
  register: (data: {
    username: string;
    password: string;
    email: string;
    fullName?: string;
    age?: number;
    gender?: string;
  }) => Promise<void>;
  logout: () => void;
  isLoading: boolean;
}

const AuthContext = createContext<AuthContextType | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<AuthResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const stored = localStorage.getItem('user');
    if (stored) {
      setUser(JSON.parse(stored));
    }
    setIsLoading(false);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const response = await api.login(username, password);
    localStorage.setItem('token', response.token);
    localStorage.setItem('user', JSON.stringify(response));
    setUser(response);
  }, []);

  const register = useCallback(async (data: {
    username: string;
    password: string;
    email: string;
    fullName?: string;
    age?: number;
    gender?: string;
  }) => {
    const response = await api.register(data);
    localStorage.setItem('token', response.token);
    localStorage.setItem('user', JSON.stringify(response));
    setUser(response);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setUser(null);
  }, []);

  return (
    <AuthContext.Provider value={{ user, login, register, logout, isLoading }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider');
  return context;
}
