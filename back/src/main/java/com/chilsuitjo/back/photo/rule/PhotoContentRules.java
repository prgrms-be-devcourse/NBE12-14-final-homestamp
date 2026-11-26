package com.chilsuitjo.back.photo.rule;

import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.jpeg.JpegProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 사진 파일 내용 검사(04 5-1 "위치 정보 제거"). 브라우저가 캔버스로 다시 그리면 원본 EXIF는 모두 사라지고, 사파리 등이 새로 붙이는 Exif 머리에는 위치가
 * 없어 통과한다. 그래도 서버가 앞부분을 읽어 JPEG인지, GPS 태그가 없는지 다시 본다.
 *
 * <p>읽지 못하면 막는다(fail-closed): 위치 정보가 남은 사진이 상대·링크로 퍼지면 되돌릴 수 없다(위치정보법 제15조, 06 PHO-1a).
 */
public final class PhotoContentRules {

  /** 서버가 읽는 앞부분 크기. 다시 그린 JPEG의 머리(APP 조각)는 이 안에 다 들어간다. */
  public static final int CHECK_BYTES = 64 * 1024;

  private PhotoContentRules() {}

  /** JPEG 시작 바이트(FF D8)로 시작하고 GPS 태그가 없으면 true. */
  public static boolean isJpegWithoutLocation(byte[] start) {
    if (start.length < 2 || (start[0] & 0xFF) != 0xFF || (start[1] & 0xFF) != 0xD8) {
      return false;
    }
    try {
      Metadata metadata = JpegMetadataReader.readMetadata(new ByteArrayInputStream(start));
      for (GpsDirectory gps : metadata.getDirectoriesOfType(GpsDirectory.class)) {
        if (gps.getTagCount() > 0) {
          return false;
        }
      }
      return true;
    } catch (JpegProcessingException | IOException e) {
      return false;
    }
  }
}
