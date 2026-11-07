/**
 * RFC 9457 Problem Details as defined in LD-API-001 §2 (application/problem+json),
 * with LivingDocs extensions `code` and `correlationId`.
 */
export interface Problem {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  code?: string;
  correlationId?: string;
  violations?: Array<{ field?: string; message?: string }>;
}

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.title || problem.code || `HTTP ${problem.status}`);
    this.name = 'ApiError';
    this.problem = problem;
  }

  get status(): number {
    return this.problem.status;
  }

  get code(): string | undefined {
    return this.problem.code;
  }
}

/** Network failure / Gateway unreachable — never treated as a business outcome. */
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super('Không kết nối được tới máy chủ (Gateway).');
    this.name = 'NetworkError';
    this.cause = cause;
  }
}

export async function toProblem(response: Response): Promise<Problem> {
  const contentType = response.headers.get('Content-Type') ?? '';
  if (contentType.includes('json')) {
    try {
      const body = (await response.json()) as Partial<Problem>;
      return { ...body, status: typeof body.status === 'number' ? body.status : response.status };
    } catch {
      // fall through to a generic problem
    }
  }
  return { status: response.status, title: response.statusText || undefined };
}

/**
 * Human-readable Vietnamese message for a failed request. Deliberately does not
 * distinguish "not found" from "belongs to another tenant" (404 may be used to
 * avoid resource enumeration, LD-API-001 §2).
 */
export function describeError(error: unknown): string {
  if (error instanceof NetworkError) return error.message;
  if (!(error instanceof ApiError)) return 'Đã xảy ra lỗi không xác định.';

  switch (error.code) {
    case 'FORBIDDEN_SCOPE':
    case 'PERMISSION_REVOKED':
      return 'Bạn không có quyền thực hiện thao tác này trong phạm vi hiện tại.';
    case 'STATE_TRANSITION_FORBIDDEN':
      return 'Phiên bản không còn ở bước Staff Review nên không thể ra quyết định.';
    case 'REVISION_CONFLICT':
      return 'Phiên bản vừa được người khác cập nhật. Dữ liệu đã được tải lại, vui lòng kiểm tra rồi thử lại.';
    case 'IDEMPOTENCY_CONFLICT':
      return 'Yêu cầu trùng khóa nhưng nội dung khác. Vui lòng thử lại.';
    case 'SOURCE_CHANGED_REVALIDATION_REQUIRED':
      return 'Nguồn (source) đã thay đổi, phiên bản cần được xác minh lại trước khi review.';
    default:
      break;
  }

  switch (error.status) {
    case 400:
    case 422:
      return error.problem.detail || 'Dữ liệu gửi lên không hợp lệ.';
    case 401:
      return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
    case 403:
    case 404:
      return 'Không tìm thấy phiên bản tài liệu hoặc bạn không có quyền truy cập.';
    case 409:
      return 'Trạng thái phiên bản không cho phép thao tác này.';
    case 412:
      return 'Phiên bản vừa được người khác cập nhật. Vui lòng tải lại.';
    case 428:
      return 'Thiếu điều kiện phiên bản (If-Match). Vui lòng tải lại trang.';
    case 429:
      return 'Bạn thao tác quá nhanh, vui lòng thử lại sau.';
    case 502:
    case 503:
    case 504:
      return 'Dịch vụ tạm thời không khả dụng. Vui lòng thử lại sau.';
    default:
      return error.problem.detail || error.message;
  }
}
