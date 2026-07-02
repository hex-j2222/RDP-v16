package com.undatech.opaque

/**
 * Plain data holder describing a VNC (RFB) connection target, consumed by
 * [RfbConnectable] (a hand-written, pure-Kotlin RFB client — see that class
 * for protocol details). No external VNC library or native dependency is
 * required.
 */
class Connection {
    var address: String = ""
    var port: Int = 5900
    var password: String = ""
    /** نوع الإدخال — يتطابق مع ثوابت RemotePointer.INPUT_MODE_* */
    var inputMode: String = ""
    var userName: String = ""
    var rdpDomain: String = ""
}

