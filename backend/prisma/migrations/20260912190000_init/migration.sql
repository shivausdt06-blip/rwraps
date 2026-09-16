-- CreateEnum
CREATE TYPE "EnrollmentState" AS ENUM ('PENDING', 'ACTIVE', 'REVOKED');

-- CreateEnum
CREATE TYPE "AuthorizationState" AS ENUM ('NONE', 'GRANTED', 'REVOKED');

-- CreateEnum
CREATE TYPE "ConnectionState" AS ENUM ('OFFLINE', 'ONLINE');

-- CreateEnum
CREATE TYPE "PairingStatus" AS ENUM ('PENDING', 'CLAIMED', 'COMPLETED', 'EXPIRED', 'REVOKED');

-- CreateEnum
CREATE TYPE "EnrollmentStatus" AS ENUM ('ACTIVE', 'REVOKED');

-- CreateEnum
CREATE TYPE "RemoteSessionStatus" AS ENUM ('CREATED', 'AUTHENTICATED', 'ACTIVE', 'RECONNECTING', 'TERMINATED', 'REVOKED', 'TIMED_OUT');

-- CreateEnum
CREATE TYPE "BackupStatus" AS ENUM ('CREATED', 'UPLOADING', 'COMPLETE', 'CANCELLED', 'FAILED', 'DELETED');

-- CreateEnum
CREATE TYPE "UploadState" AS ENUM ('PENDING', 'IN_PROGRESS', 'COMPLETE', 'CANCELLED', 'FAILED');

-- CreateEnum
CREATE TYPE "ActorType" AS ENUM ('ADMIN', 'DEVICE', 'SYSTEM');

-- CreateTable
CREATE TABLE "Admin" (
    "id" UUID NOT NULL,
    "email" TEXT NOT NULL,
    "passwordHash" TEXT NOT NULL,
    "displayName" TEXT NOT NULL,
    "tokenVersion" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Admin_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RefreshToken" (
    "id" UUID NOT NULL,
    "adminId" UUID NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "RefreshToken_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Device" (
    "id" UUID NOT NULL,
    "ownerAdminId" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "enrollmentState" "EnrollmentState" NOT NULL DEFAULT 'PENDING',
    "authorizationState" "AuthorizationState" NOT NULL DEFAULT 'NONE',
    "platform" TEXT NOT NULL DEFAULT 'android',
    "androidVersion" TEXT,
    "manufacturer" TEXT,
    "model" TEXT,
    "sdkInt" INTEGER,
    "lastSeenAt" TIMESTAMP(3),
    "connectionState" "ConnectionState" NOT NULL DEFAULT 'OFFLINE',
    "capabilities" JSONB NOT NULL,
    "tokenVersion" INTEGER NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Device_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "DeviceRefreshToken" (
    "id" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "tokenHash" TEXT NOT NULL,
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DeviceRefreshToken_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Enrollment" (
    "id" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "adminId" UUID NOT NULL,
    "pairingSessionId" UUID,
    "status" "EnrollmentStatus" NOT NULL DEFAULT 'ACTIVE',
    "revokedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Enrollment_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "PairingSession" (
    "id" UUID NOT NULL,
    "adminId" UUID NOT NULL,
    "codeHash" TEXT NOT NULL,
    "nonce" TEXT NOT NULL,
    "status" "PairingStatus" NOT NULL DEFAULT 'PENDING',
    "expiresAt" TIMESTAMP(3) NOT NULL,
    "claimedAt" TIMESTAMP(3),
    "usedAt" TIMESTAMP(3),
    "revokedAt" TIMESTAMP(3),
    "deviceId" UUID,
    "pendingDeviceInfo" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "PairingSession_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "RemoteSession" (
    "id" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "adminId" UUID NOT NULL,
    "status" "RemoteSessionStatus" NOT NULL DEFAULT 'CREATED',
    "startedAt" TIMESTAMP(3),
    "endedAt" TIMESTAMP(3),
    "timeoutAt" TIMESTAMP(3),
    "reconnectCount" INTEGER NOT NULL DEFAULT 0,
    "quality" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "RemoteSession_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Backup" (
    "id" UUID NOT NULL,
    "deviceId" UUID NOT NULL,
    "adminId" UUID NOT NULL,
    "status" "BackupStatus" NOT NULL DEFAULT 'CREATED',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Backup_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "BackupFile" (
    "id" UUID NOT NULL,
    "backupId" UUID NOT NULL,
    "filename" TEXT NOT NULL,
    "sizeBytes" BIGINT NOT NULL,
    "mimeType" TEXT NOT NULL,
    "checksumSha256" TEXT NOT NULL,
    "uploadState" "UploadState" NOT NULL DEFAULT 'PENDING',
    "storageKey" TEXT NOT NULL,
    "bytesUploaded" BIGINT NOT NULL DEFAULT 0,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "BackupFile_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "AuditEvent" (
    "id" UUID NOT NULL,
    "actorType" "ActorType" NOT NULL,
    "actorAdminId" UUID,
    "actorDeviceId" UUID,
    "action" TEXT NOT NULL,
    "resourceType" TEXT NOT NULL,
    "resourceId" TEXT,
    "ipAddress" TEXT,
    "userAgent" TEXT,
    "metadata" JSONB,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "AuditEvent_pkey" PRIMARY KEY ("id")
);

CREATE UNIQUE INDEX "Admin_email_key" ON "Admin"("email");
CREATE UNIQUE INDEX "RefreshToken_tokenHash_key" ON "RefreshToken"("tokenHash");
CREATE INDEX "RefreshToken_adminId_idx" ON "RefreshToken"("adminId");
CREATE INDEX "RefreshToken_expiresAt_idx" ON "RefreshToken"("expiresAt");
CREATE INDEX "Device_ownerAdminId_idx" ON "Device"("ownerAdminId");
CREATE INDEX "Device_connectionState_idx" ON "Device"("connectionState");
CREATE INDEX "Device_lastSeenAt_idx" ON "Device"("lastSeenAt");
CREATE UNIQUE INDEX "DeviceRefreshToken_tokenHash_key" ON "DeviceRefreshToken"("tokenHash");
CREATE INDEX "DeviceRefreshToken_deviceId_idx" ON "DeviceRefreshToken"("deviceId");
CREATE UNIQUE INDEX "Enrollment_pairingSessionId_key" ON "Enrollment"("pairingSessionId");
CREATE INDEX "Enrollment_deviceId_idx" ON "Enrollment"("deviceId");
CREATE INDEX "Enrollment_adminId_idx" ON "Enrollment"("adminId");
CREATE INDEX "Enrollment_status_idx" ON "Enrollment"("status");
CREATE UNIQUE INDEX "PairingSession_codeHash_key" ON "PairingSession"("codeHash");
CREATE UNIQUE INDEX "PairingSession_nonce_key" ON "PairingSession"("nonce");
CREATE INDEX "PairingSession_adminId_idx" ON "PairingSession"("adminId");
CREATE INDEX "PairingSession_status_expiresAt_idx" ON "PairingSession"("status", "expiresAt");
CREATE INDEX "RemoteSession_deviceId_status_idx" ON "RemoteSession"("deviceId", "status");
CREATE INDEX "RemoteSession_adminId_createdAt_idx" ON "RemoteSession"("adminId", "createdAt");
CREATE INDEX "Backup_deviceId_idx" ON "Backup"("deviceId");
CREATE INDEX "Backup_adminId_idx" ON "Backup"("adminId");
CREATE UNIQUE INDEX "BackupFile_storageKey_key" ON "BackupFile"("storageKey");
CREATE INDEX "BackupFile_backupId_idx" ON "BackupFile"("backupId");
CREATE INDEX "AuditEvent_actorAdminId_createdAt_idx" ON "AuditEvent"("actorAdminId", "createdAt");
CREATE INDEX "AuditEvent_resourceType_resourceId_idx" ON "AuditEvent"("resourceType", "resourceId");
CREATE INDEX "AuditEvent_createdAt_idx" ON "AuditEvent"("createdAt");
CREATE INDEX "AuditEvent_action_idx" ON "AuditEvent"("action");

ALTER TABLE "RefreshToken" ADD CONSTRAINT "RefreshToken_adminId_fkey" FOREIGN KEY ("adminId") REFERENCES "Admin"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Device" ADD CONSTRAINT "Device_ownerAdminId_fkey" FOREIGN KEY ("ownerAdminId") REFERENCES "Admin"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "DeviceRefreshToken" ADD CONSTRAINT "DeviceRefreshToken_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "Device"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Enrollment" ADD CONSTRAINT "Enrollment_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "Device"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Enrollment" ADD CONSTRAINT "Enrollment_adminId_fkey" FOREIGN KEY ("adminId") REFERENCES "Admin"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "Enrollment" ADD CONSTRAINT "Enrollment_pairingSessionId_fkey" FOREIGN KEY ("pairingSessionId") REFERENCES "PairingSession"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "PairingSession" ADD CONSTRAINT "PairingSession_adminId_fkey" FOREIGN KEY ("adminId") REFERENCES "Admin"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "PairingSession" ADD CONSTRAINT "PairingSession_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "Device"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "RemoteSession" ADD CONSTRAINT "RemoteSession_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "Device"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "RemoteSession" ADD CONSTRAINT "RemoteSession_adminId_fkey" FOREIGN KEY ("adminId") REFERENCES "Admin"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "Backup" ADD CONSTRAINT "Backup_deviceId_fkey" FOREIGN KEY ("deviceId") REFERENCES "Device"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "Backup" ADD CONSTRAINT "Backup_adminId_fkey" FOREIGN KEY ("adminId") REFERENCES "Admin"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "BackupFile" ADD CONSTRAINT "BackupFile_backupId_fkey" FOREIGN KEY ("backupId") REFERENCES "Backup"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "AuditEvent" ADD CONSTRAINT "AuditEvent_actorAdminId_fkey" FOREIGN KEY ("actorAdminId") REFERENCES "Admin"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "AuditEvent" ADD CONSTRAINT "AuditEvent_actorDeviceId_fkey" FOREIGN KEY ("actorDeviceId") REFERENCES "Device"("id") ON DELETE SET NULL ON UPDATE CASCADE;
