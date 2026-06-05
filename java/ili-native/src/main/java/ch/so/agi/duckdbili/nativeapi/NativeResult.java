package ch.so.agi.duckdbili.nativeapi;

import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.struct.CField;

/**
 * C struct matching {@code ili_result} on the C side.
 */
@CStruct(value = "ili_result", isIncomplete = true)
public interface NativeResult extends org.graalvm.word.PointerBase {

    @CField("code")
    int getCode();

    @CField("code")
    void setCode(int value);

    @CField("payload")
    CCharPointer getPayload();

    @CField("payload")
    void setPayload(CCharPointer value);

    @CField("error_message")
    CCharPointer getErrorMessage();

    @CField("error_message")
    void setErrorMessage(CCharPointer value);
}
