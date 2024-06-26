/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
 * Licensed under the MIT License.
 */
package ai.onnxruntime;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * A container for a map returned by {@link OrtSession#run(Map)}.
 *
 * <p>Supported types are those mentioned in {@code onnxruntime_c_api.h}, keys: String and Long,
 * values: String, Long, Float, Double.
 */
public class OnnxMap implements OnnxValue {
  private static final Logger logger = Logger.getLogger(OnnxMap.class.getName());

  static {
    try {
      OnnxRuntime.init();
    } catch (IOException e) {
      throw new RuntimeException("Failed to load onnx-runtime library", e);
    }
  }

  /** An enum representing the Java type of the values stored in an {@link OnnxMap}. */
  public enum OnnxMapValueType {
    /** An invalid Map value type. */
    INVALID(0),
    /** A String value. */
    STRING(1),
    /** A 64-bit signed integer value. */
    LONG(2),
    /** A 32-bit floating point value. */
    FLOAT(3),
    /** A 64-bit floating point value. */
    DOUBLE(4);

    /** The native enum value. */
    final int value;

    OnnxMapValueType(int value) {
      this.value = value;
    }

    private static final OnnxMapValueType[] values = new OnnxMapValueType[5];

    static {
      for (OnnxMapValueType ot : OnnxMapValueType.values()) {
        values[ot.value] = ot;
      }
    }

    /**
     * Gets the enum type from it's integer id. Used by native code.
     *
     * @param value The integer id.
     * @return The enum instance.
     */
    public static OnnxMapValueType mapFromInt(int value) {
      if ((value > 0) && (value < values.length)) {
        return values[value];
      } else {
        return INVALID;
      }
    }

    /**
     * Maps a {@link OnnxJavaType} into a map value type. If it's not a valid map type return {@link
     * OnnxMapValueType#INVALID}.
     *
     * @param type The Java type.
     * @return The equivalent Map value type.
     */
    public static OnnxMapValueType mapFromOnnxJavaType(OnnxJavaType type) {
      switch (type) {
        case FLOAT:
          return OnnxMapValueType.FLOAT;
        case DOUBLE:
          return OnnxMapValueType.DOUBLE;
        case INT64:
          return OnnxMapValueType.LONG;
        case STRING:
          return OnnxMapValueType.STRING;
        case UINT8:
        case INT8:
        case INT16:
        case INT32:
        case BOOL:
        case UNKNOWN:
        default:
          return OnnxMapValueType.INVALID;
      }
    }
  }

  /** The native pointer. */
  final long nativeHandle;

  /** The pointer to the allocator used by this {@code OnnxMap}. */
  final long allocatorHandle;

  private final MapInfo info;

  private final boolean stringKeys;

  private final OnnxMapValueType valueType;

  private boolean closed;

  /**
   * Constructs an OnnxMap containing a reference to the native map along with the type information.
   *
   * <p>Called from native code.
   *
   * @param nativeHandle The reference to the native map object.
   * @param allocatorHandle The reference to the allocator that created the map.
   * @param info The type information.
   */
  OnnxMap(long nativeHandle, long allocatorHandle, MapInfo info) {
    this.nativeHandle = nativeHandle;
    this.allocatorHandle = allocatorHandle;
    this.info = info;
    this.stringKeys = info.keyType == OnnxJavaType.STRING;
    this.valueType = OnnxMapValueType.mapFromOnnxJavaType(info.valueType);
    this.closed = false;
  }

  /**
   * The number of entries in the map.
   *
   * @return The number of entries.
   */
  public int size() {
    return info.size;
  }

  @Override
  public OnnxValueType getType() {
    return OnnxValueType.ONNX_TYPE_MAP;
  }

  /**
   * Returns a weakly typed Map containing all the elements.
   *
   * @return A map.
   * @throws OrtException If the onnx runtime failed to read the entries.
   */
  @Override
  public Map<? extends Object, ? extends Object> getValue() throws OrtException {
    checkClosed();
    Object[] keys = getMapKeys();
    Object[] values = getMapValues();
    HashMap<Object, Object> map = new HashMap<>(OrtUtil.capacityFromSize(keys.length));
    for (int i = 0; i < keys.length; i++) {
      map.put(keys[i], values[i]);
    }
    return map;
  }

  /**
   * Extracts the map keys, boxing the primitives as necessary.
   *
   * @return The keys from the map as an array.
   * @throws OrtException If the onnxruntime failed to read the keys.
   */
  private Object[] getMapKeys() throws OrtException {
    if (stringKeys) {
      return getStringKeys(OnnxRuntime.ortApiHandle, nativeHandle, allocatorHandle);
    } else {
      return Arrays.stream(getLongKeys(OnnxRuntime.ortApiHandle, nativeHandle, allocatorHandle))
          .boxed()
          .toArray();
    }
  }

  /**
   * Extracts the map values, boxing primitives as necessary.
   *
   * @return The values from the map as an array.
   * @throws OrtException If the onnxruntime failed to read the values.
   */
  private Object[] getMapValues() throws OrtException {
    switch (valueType) {
      case STRING:
        {
          return getStringValues(OnnxRuntime.ortApiHandle, nativeHandle, allocatorHandle);
        }
      case LONG:
        {
          return Arrays.stream(
                  getLongValues(OnnxRuntime.ortApiHandle, nativeHandle, allocatorHandle))
              .boxed()
              .toArray();
        }
      case FLOAT:
        {
          float[] floats = getFloatValues(OnnxRuntime.ortApiHandle, nativeHandle, allocatorHandle);
          Float[] boxed = new Float[floats.length];
          for (int i = 0; i < floats.length; i++) {
            // cast float to Float
            boxed[i] = floats[i];
          }
          return boxed;
        }
      case DOUBLE:
        {
          return Arrays.stream(
                  getDoubleValues(OnnxRuntime.ortApiHandle, nativeHandle, allocatorHandle))
              .boxed()
              .toArray();
        }
      default:
        throw new RuntimeException("Invalid or unknown valueType: " + valueType);
    }
  }

  @Override
  public MapInfo getInfo() {
    return info;
  }

  @Override
  public String toString() {
    return "ONNXMap(size=" + size() + ",info=" + info.toString() + ")";
  }

  @Override
  public synchronized boolean isClosed() {
    return closed;
  }

  /** Closes this map, releasing the native memory backing it and it's elements. */
  @Override
  public synchronized void close() {
    if (!closed) {
      close(OnnxRuntime.ortApiHandle, nativeHandle);
      closed = true;
    } else {
      logger.warning("Closing an already closed map.");
    }
  }

  /** Checks if the OnnxValue is closed, if so throws {@link IllegalStateException}. */
  protected void checkClosed() {
    if (closed) {
      throw new IllegalStateException("Trying to use a closed OnnxValue");
    }
  }

  private native String[] getStringKeys(long apiHandle, long nativeHandle, long allocatorHandle)
      throws OrtException;

  private native long[] getLongKeys(long apiHandle, long nativeHandle, long allocatorHandle)
      throws OrtException;

  private native String[] getStringValues(long apiHandle, long nativeHandle, long allocatorHandle)
      throws OrtException;

  private native long[] getLongValues(long apiHandle, long nativeHandle, long allocatorHandle)
      throws OrtException;

  private native float[] getFloatValues(long apiHandle, long nativeHandle, long allocatorHandle)
      throws OrtException;

  private native double[] getDoubleValues(long apiHandle, long nativeHandle, long allocatorHandle)
      throws OrtException;

  private native void close(long apiHandle, long nativeHandle);
}
