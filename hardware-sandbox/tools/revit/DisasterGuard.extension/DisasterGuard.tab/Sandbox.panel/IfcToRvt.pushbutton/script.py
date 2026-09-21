# -*- coding: utf-8 -*-
"""
GL-Sandbox · IFC → RVT 一键转换
====================================================================
适用:Revit 2021+(本工具在 Revit 2024 上验证),pyRevit 4.8+
行为:
  1. 列出 hardware-sandbox 仓库内已生成的 3 个 IFC 文件供选择
  2. 调用 Revit API Application.OpenDocumentFile() 直接打开 IFC
     (Revit 2021+ 内置 IFC 插件会自动把 IfcTriangulatedFaceSet / IfcBuilding
      / IfcRoad / IfcBridge 等转成 native Revit 几何)
  3. SaveAs() 为同名 .rvt,放到 IFC 同目录
失败回退:若 OpenDocumentFile 不接受 .ifc 路径,弹窗提示用户手动在
         Revit 里 File → Open 选 IFC 后再 File → Save As 项目(*.rvt)
====================================================================
"""
import os
import sys
import traceback

from pyrevit import revit, DB, forms

# hardware-sandbox 仓库内的 3 个 IFC 候选文件
SANDBOX_ROOT = r"D:\DisasterGuard\hardware-sandbox\examples"
IFC_CANDIDATES = [
    (
        "Sandbox 沙盘模型 (1.2 × 0.8 m, 1:2421, VE=2.5)",
        os.path.join(SANDBOX_ROOT, "revit_sandbox", "model.ifc"),
    ),
    (
        "Real 真实地形 (1:1, VE=1, GIS 对齐)",
        os.path.join(SANDBOX_ROOT, "revit_model", "model.ifc"),
    ),
    (
        "Terrain 仅地形 (3 km, 最小)",
        os.path.join(SANDBOX_ROOT, "revit_terrain", "terrain.ifc"),
    ),
]

BROWSE_LABEL = "(浏览其它 IFC 文件...)"


def _pick_ifc():
    """弹出选择对话框,返回选中的 IFC 绝对路径;用户取消返回 None。"""
    existing = [(label, path) for label, path in IFC_CANDIDATES if os.path.exists(path)]
    existing.append((BROWSE_LABEL, None))

    labels = [label for label, _ in existing]
    picked = forms.SelectFromList.show(
        labels,
        title="GL-Sandbox · IFC → RVT",
        button_name="下一步 →",
        width=560,
        multiselect=False,
    )
    if not picked:
        return None

    if picked == BROWSE_LABEL:
        path = forms.pick_file(file_ext="ifc", title="选择 IFC 文件")
        return path

    for label, path in existing:
        if label == picked:
            return path
    return None


def _confirm_overwrite(rvt_path):
    if not os.path.exists(rvt_path):
        return True
    return forms.alert(
        "目标 RVT 已存在:\n{}\n\n是否覆盖?".format(rvt_path),
        yes=True,
        no=True,
    )


def _open_ifc_and_save_as_rvt(ifc_path, rvt_path):
    """
    核心流程:
      - revit.app.OpenDocumentFile(ifc_path) 让 Revit 直接打开 IFC
      - 新打开的 doc.SaveAs(rvt_path, ...) 落盘成 RVT
    若 OpenDocumentFile 不接受 .ifc 路径,会抛异常,我们在外层捕获并提示手动操作。
    """
    app = revit.app
    if app is None:
        raise RuntimeError("无法访问 Revit Application 对象,pyRevit 是否已正确附加到 Revit?")

    # 记录当前打开的 doc,转换完后切回去(不强制,但用户体验更好)
    original_doc = revit.doc

    try:
        new_doc = app.OpenDocumentFile(ifc_path)
    except Exception as ex:
        raise RuntimeError(
            "Revit 无法直接打开 IFC ({}).\n"
            "请在 Revit 里 File → Open 选 {} 后,\n"
            "再 File → Save As 项目 (*.rvt) 手动另存。".format(ex, ifc_path)
        )

    if new_doc is None:
        raise RuntimeError("OpenDocumentFile 返回空 Document,可能 IFC 插件未启用。")

    try:
        save_opts = DB.SaveAsOptions()
        save_opts.OverwriteExistingFile = True
        # 不强制切换工作集配置,避免引入额外提示
        new_doc.SaveAs(rvt_path, save_opts)
    except Exception as ex:
        raise RuntimeError("另存为 RVT 失败: {}".format(ex))
    finally:
        # 关闭刚生成的 doc,避免堆在 Revit 里
        try:
            new_doc.Close(False)
        except Exception:
            pass

    return rvt_path


def main():
    ifc_path = _pick_ifc()
    if not ifc_path:
        return

    if not os.path.exists(ifc_path):
        forms.alert("IFC 文件不存在:\n{}".format(ifc_path), warn_icon=True)
        return

    rvt_path = os.path.splitext(ifc_path)[0] + ".rvt"
    if not _confirm_overwrite(rvt_path):
        return

    forms.alert(
        "即将:\n"
        "  1. 在 Revit 中打开 IFC:\n     {}\n"
        "  2. 另存为 RVT:\n     {}\n\n"
        "过程中 Revit 会闪一下(打开/关闭临时 doc)。点 OK 开始。".format(ifc_path, rvt_path),
        warn_icon=True,
    )

    try:
        out = _open_ifc_and_save_as_rvt(ifc_path, rvt_path)
    except Exception as ex:
        forms.alert(
            "转换失败:\n{}\n\n详细:\n{}".format(ex, traceback.format_exc()),
            warn_icon=True,
        )
        return

    if out and os.path.exists(out):
        size_kb = os.path.getsize(out) / 1024.0
        forms.alert(
            "✓ 转换完成\n\n文件: {}\n大小: {:.1f} KB\n\n下一步:在 Revit 里 File → Open 打开这个 .rvt 即可。".format(
                out, size_kb
            ),
            info_icon=True,
        )
    else:
        forms.alert("转换流程结束,但未找到目标文件:\n{}".format(rvt_path), warn_icon=True)


if __name__ == "__main__":
    main()
