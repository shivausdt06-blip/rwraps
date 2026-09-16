import type { FastifyRequest } from "fastify";

export function requestMeta(request: FastifyRequest) {
  return {
    ipAddress: request.ip,
    userAgent: request.headers["user-agent"]
  };
}
