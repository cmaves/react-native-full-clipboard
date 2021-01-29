import { NativeModules } from 'react-native';

type FullClipboardType = {
  multiply(a: number, b: number): Promise<number>;
};

const { FullClipboard } = NativeModules;

export default FullClipboard as FullClipboardType;
