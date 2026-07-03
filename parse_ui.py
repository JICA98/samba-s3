import xml.etree.ElementTree as ET
try:
    tree = ET.parse("ui_dump2.xml")
    root = tree.getroot()
    def print_tree(node, level=0):
        text = node.attrib.get('text', '')
        content_desc = node.attrib.get('content-desc', '')
        clazz = node.attrib.get('class', '')
        bounds = node.attrib.get('bounds', '')
        print("  " * level + f"[{clazz}] text='{text}' desc='{content_desc}' bounds='{bounds}'")
        for child in node:
            print_tree(child, level + 1)
    print_tree(root)
except Exception as e:
    print(f"Error parsing XML: {e}")
