package com.chilsuitjo.back.photo.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/**
 * 테스트용 사진 바이트. 브라우저가 캔버스로 다시 그린 JPEG(EXIF 없음), 사파리처럼 새 Exif 머리가 붙었지만 위치는 없는 JPEG, GPS 태그가 남은 JPEG,
 * JPEG가 아닌 파일(04 5-1 "위치 정보 제거").
 */
final class TestJpegs {

  private static final short TAG_ORIENTATION = 0x0112;
  private static final short TAG_GPS_IFD = (short) 0x8825;
  private static final short TAG_GPS_LATITUDE_REF = 0x0001;
  private static final short TYPE_ASCII = 2;
  private static final short TYPE_SHORT = 3;
  private static final short TYPE_LONG = 4;

  private TestJpegs() {}

  /** 캔버스에 다시 그린 JPEG처럼 EXIF가 없다. */
  static byte[] redrawn() {
    try {
      BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "jpg", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 다시 그린 JPEG에 방향만 담은 Exif 머리를 붙인다(사파리). 위치가 없으니 통과해야 한다. */
  static byte[] withExifWithoutGps() {
    ByteBuffer tiff = tiffHeader(26);
    tiff.putShort((short) 1); // IFD0 항목 수
    entry(tiff, TAG_ORIENTATION, TYPE_SHORT, 1, 1);
    tiff.putInt(0); // 다음 IFD 없음
    return insertExif(redrawn(), tiff);
  }

  /** 위치 정보(GPS IFD, 위도 방향 'N')가 남은 JPEG. */
  static byte[] withGps() {
    ByteBuffer tiff = tiffHeader(44);
    int gpsIfd = 8 + 2 + 12 + 4;
    tiff.putShort((short) 1);
    entry(tiff, TAG_GPS_IFD, TYPE_LONG, 1, gpsIfd);
    tiff.putInt(0);
    tiff.putShort((short) 1);
    entry(tiff, TAG_GPS_LATITUDE_REF, TYPE_ASCII, 2, 'N');
    tiff.putInt(0);
    return insertExif(redrawn(), tiff);
  }

  /** 형식 꼬리표만 image/jpeg인 PNG. */
  static byte[] notJpeg() {
    byte[] png = new byte[64];
    byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    System.arraycopy(signature, 0, png, 0, signature.length);
    return png;
  }

  private static ByteBuffer tiffHeader(int size) {
    ByteBuffer tiff = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
    return tiff;
  }

  /** 값이 4바이트 안에 들어가는 항목. 작은 값은 앞쪽부터 채운다(little endian). */
  private static void entry(ByteBuffer tiff, short tag, short type, int count, int value) {
    tiff.putShort(tag).putShort(type).putInt(count).putInt(value);
  }

  /** SOI(FF D8) 바로 뒤에 APP1 Exif 조각을 넣는다. */
  private static byte[] insertExif(byte[] jpeg, ByteBuffer tiff) {
    byte[] exif = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    byte[] body = tiff.array();
    int length = 2 + exif.length + body.length;
    ByteBuffer out = ByteBuffer.allocate(jpeg.length + 2 + length);
    out.put(jpeg, 0, 2);
    out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) length);
    out.put(exif).put(body);
    out.put(jpeg, 2, jpeg.length - 2);
    return out.array();
  }
}
