package ch.so.agi.duckdbili.nativeapi;

import org.graalvm.nativeimage.c.CContext;
import org.graalvm.nativeimage.c.struct.CField;
import org.graalvm.nativeimage.c.struct.CStruct;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;

@CContext(IliDirectives.class)
@CStruct("ili_request")
public interface IliRequest extends PointerBase {

    @CField("struct_size")
    int struct_size();

    @CField("struct_size")
    void struct_size(int value);

    @CField("input")
    CCharPointer input();

    @CField("input")
    void input(CCharPointer value);

    @CField("modeldir")
    CCharPointer modeldir();

    @CField("modeldir")
    void modeldir(CCharPointer value);

    @CField("cmd")
    CCharPointer cmd();

    @CField("cmd")
    void cmd(CCharPointer value);

    @CField("class_name")
    CCharPointer class_name();

    @CField("class_name")
    void class_name(CCharPointer value);

    @CField("models")
    CCharPointer models();

    @CField("models")
    void models(CCharPointer value);

    @CField("model")
    CCharPointer model();

    @CField("model")
    void model(CCharPointer value);

    @CField("association")
    CCharPointer association();

    @CField("association")
    void association(CCharPointer value);

    @CField("schema")
    CCharPointer schema();

    @CField("schema")
    void schema(CCharPointer value);

    @CField("nested")
    CCharPointer nested();

    @CField("nested")
    void nested(CCharPointer value);

    @CField("mapping")
    CCharPointer mapping();

    @CField("mapping")
    void mapping(CCharPointer value);

    @CField("max_messages")
    int max_messages();

    @CField("max_messages")
    void max_messages(int value);

    @CField("profile")
    CCharPointer profile();

    @CField("profile")
    void profile(CCharPointer value);

    @CField("mode")
    CCharPointer mode();

    @CField("mode")
    void mode(CCharPointer value);
}
