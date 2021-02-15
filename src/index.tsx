import { sha256 } from 'js-sha256';
import { NativeModules } from 'react-native';

type FullClipboardType = {
  multiply(a: number, b: number): Promise<number>;
  newListener(mimes: string[]): number;
  destroyListener(i: number): void;
  getNextClip(i: number): Promise<RNClip>;
  getCurClip(i: number): Promise<RNClip>;
  setClipboard(clip: RNClip): Promise<void>;
};

const FullClipboard: FullClipboardType = NativeModules.FullClipboard;
/*
interface ClipInterface  {
    data: number[];
    mime: string;
    eq(other: Clip): boolean;
    constructor(data:ArrayBuffer, mime: string);

}
export FullClipboard.Clip as ClipInterface;
*/

interface RNClip {
  mime: string;
  dataArray: number[];
}

export function multiply(a: number, b: number): Promise<number> {
  console.log(FullClipboard);
  return FullClipboard.multiply(a, b);
}
export function setClipboard(clip: Clip) {
  return FullClipboard.setClipboard(clip.toRNClip());
}

export class CbListener {
  id: number | Promise<number>;

  constructor(mimes: string[]) {
    console.log('Building new CbListener()');
    this.id = FullClipboard.newListener(mimes);
  }
  async getNextClip(): Promise<Clip> {
    if (typeof this.id === 'number') {
      return Clip.fromRNClip(await FullClipboard.getNextClip(this.id));
    } else {
      this.id = await this.id;
      return await this.getNextClip();
    }
  }
  async getCurClip(): Promise<Clip> {
    if (typeof this.id === 'number') {
      return Clip.fromRNClip(await FullClipboard.getCurClip(this.id));
      // return (await FullClipboard.getCurClip(this.id)).toClip();
    } else {
      this.id = await this.id;
      return await this.getCurClip();
    }
  }
  close() {
    if (typeof this.id === 'number') {
      FullClipboard.destroyListener(this.id);
    } else {
      this.id = this.id.then((id) => {
        FullClipboard.destroyListener(id);
        return id;
      });
    }
  }
}

export class Clip {
  data: ArrayBuffer;
  mime: string;
  hash: number[];

  constructor(data: ArrayBuffer, mime: string) {
    this.data = data;
    this.mime = mime;
    this.hash = sha256.array(data);
  }
  static fromRNClip(clip: RNClip): Clip {
    let buffer = new ArrayBuffer(clip.dataArray.length);
    let bv = new Uint8Array(buffer);
    bv.set(clip.dataArray);
    return new Clip(buffer, clip.mime);
  }
  toRNClip(): RNClip {
    let bytes = new Uint8Array(this.data);
    return { mime: this.mime, dataArray: Array.from(bytes) };
  }
  eq(other: Clip) {
    if (this === other) {
      return true;
    }
    if (this.mime !== other.mime) {
      return false;
    }
    if (this.data.byteLength !== other.data.byteLength) {
      return false;
    }
    return this.hash === other.hash;
  }
}
