export class AppError extends Error {
  readonly statusCode: number;
  readonly code: string;
  readonly expose: boolean;

  constructor(statusCode: number, code: string, message: string, expose = true) {
    super(message);
    this.statusCode = statusCode;
    this.code = code;
    this.expose = expose;
  }
}

export function notFound(resource = "Resource"): AppError {
  return new AppError(404, "NOT_FOUND", `${resource} was not found.`);
}

export function unauthorized(message = "Authentication is required."): AppError {
  return new AppError(401, "UNAUTHORIZED", message);
}

export function forbidden(message = "You are not allowed to perform this action."): AppError {
  return new AppError(403, "FORBIDDEN", message);
}

export function conflict(code: string, message: string): AppError {
  return new AppError(409, code, message);
}

export function badRequest(code: string, message: string): AppError {
  return new AppError(400, code, message);
}
