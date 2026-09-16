type SocketRef = { send: (payload: unknown) => void };

class Hub {
  private admins = new Map<string, Set<SocketRef>>();
  private devices = new Map<string, SocketRef>();

  addAdmin(adminId: string, socket: SocketRef): void {
    const set = this.admins.get(adminId) ?? new Set<SocketRef>();
    set.add(socket);
    this.admins.set(adminId, set);
  }

  removeAdmin(adminId: string, socket: SocketRef): void {
    const set = this.admins.get(adminId);
    if (!set) return;
    set.delete(socket);
    if (set.size === 0) {
      this.admins.delete(adminId);
    }
  }

  setDevice(deviceId: string, socket: SocketRef): void {
    this.devices.set(deviceId, socket);
  }

  removeDevice(deviceId: string, socket: SocketRef): void {
    if (this.devices.get(deviceId) === socket) {
      this.devices.delete(deviceId);
    }
  }

  sendToAdmin(adminId: string, payload: unknown): boolean {
    const set = this.admins.get(adminId);
    if (!set || set.size === 0) {
      return false;
    }
    for (const socket of set) {
      socket.send(payload);
    }
    return true;
  }

  sendToDevice(deviceId: string, payload: unknown): boolean {
    const socket = this.devices.get(deviceId);
    if (!socket) return false;
    socket.send(payload);
    return true;
  }
}

export const hub = new Hub();
