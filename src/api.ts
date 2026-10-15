import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
export interface Distro { id: string; name: string; version: string; description: string; iso_url: string; checksum_url: string; iso_filename: string; signature_url: string | null; homepage: string; sha256: string | null; kind?: "linux-iso" | "rootforge-os"; image_format?: "iso" | "img"; notes?: string | null; }
export interface UsbDevice { name: string; path: string; size: number; model: string; transport: string; mounted: string[]; media: "usb" | "sd"; }
export interface DownloadResult { path: string; sha256: string; verified: boolean; gpg: string; }
export type Mode = "direct" | "usb" | "sd";
export const DIRECT_IDS = ["ubuntu", "mint"]; // mirrors flash_core::direct::loopback_recipe
export interface Progress { phase: string; done: number; total: number; }
export const api = {
  listDistros: () => invoke<Distro[]>("list_distros"),
  listDevices: () => invoke<UsbDevice[]>("list_devices"),
  download: (id: string) => invoke<DownloadResult>("download_iso", { id }),
  flash: (iso: string, device: string, typedConfirmation: string, sha256: string) =>
    invoke<string>("flash", { iso, device, typedConfirmation, sha256 }),
  directEntry: (id: string, isoPath: string) => invoke<string>("direct_entry", { id, isoPath }),
  onProgress: (cb: (p: Progress) => void) => listen<Progress>("progress", (e) => cb(e.payload)),
};
export const fmtBytes = (n: number) => { const u = ["B","KB","MB","GB","TB"]; let i = 0; while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; } return `${n.toFixed(1)} ${u[i]}`; };
