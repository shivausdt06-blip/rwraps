-- AlterEnum
ALTER TYPE "BackupStatus" ADD VALUE IF NOT EXISTS 'STAGED';

-- CreateEnum
CREATE TYPE "BackupStorageProvider" AS ENUM ('LOCAL_ADMIN', 'CLOUD');

-- CreateEnum
CREATE TYPE "BackupPayloadState" AS ENUM ('PENDING', 'STAGING', 'LOCAL_ADMIN', 'CLOUD', 'FAILED');

-- AlterTable
ALTER TABLE "Backup" ADD COLUMN "storageProvider" "BackupStorageProvider" NOT NULL DEFAULT 'LOCAL_ADMIN';
ALTER TABLE "Backup" ADD COLUMN "payloadState" "BackupPayloadState" NOT NULL DEFAULT 'PENDING';

-- AlterTable
ALTER TABLE "BackupFile" ADD COLUMN "payloadState" "BackupPayloadState" NOT NULL DEFAULT 'PENDING';
