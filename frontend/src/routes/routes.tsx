import { Navigate, Route, Routes } from 'react-router-dom';
import { LoginPage } from '../features/auth/LoginPage';
import { ReviewDetailPage } from '../features/review/ReviewDetailPage';
import { ReviewQueuePage } from '../features/review/ReviewQueuePage';
import { RequireAuth } from './guards/RequireAuth';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/review"
        element={
          <RequireAuth>
            <ReviewQueuePage />
          </RequireAuth>
        }
      />
      <Route
        path="/review/:versionId"
        element={
          <RequireAuth>
            <ReviewDetailPage />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/review" replace />} />
    </Routes>
  );
}
