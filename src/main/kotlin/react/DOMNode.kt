package react

data class DOMNode(
    var tag: String,
    var text: String? = null,
    var styleId: String? = null,
    var style: StyleSet = StyleSet(),
    var id: String? = null,
    var hasFocus: Boolean = false,

    // Event callbacks — all get UIEvent
    var onMouseDown: UIEventHandler? = null,
    var onMouseUp: UIEventHandler? = null,
    var onMouseMove: UIEventHandler? = null,
    var onMouseScroll: UIEventHandler? = null,
    var onKeyDown: UIEventHandler? = null,
    var onKeyUp: UIEventHandler? = null,
    var onFocusGained: UIEventHandler? = null,
    var onFocusLost: UIEventHandler? = null,
    var onResize: UIEventHandler? = null,

    var children: List<DOMNode> = emptyList(),
    var key: String? = "",
    var visible: Boolean = true,
) {
    fun offset(x:Int, y:Int): DOMNode {
        this.style.offset(x,y)
        this.children.forEach { it.offset(x,y) }
        return this
    }
    fun boundingBox() = style.boundingBox()
    fun contains(node: DOMNode) = boundingBox().contains(node.boundingBox())


    fun eachPre(parentLevel: Int=0, parentIndex: Int=0, block: (node: DOMNode, level: Int, index: Int)->Unit){
        block(this,parentLevel,parentIndex)
        var childIndex=0
        for (child in this.children) {
            child.eachPre(parentLevel=parentLevel+1,parentIndex=childIndex,block)
            childIndex+=1
        }
    }
    fun eachPost(parentLevel: Int=0, parentIndex: Int=0, block: (node: DOMNode, level: Int, index: Int)->Unit){
        var childIndex=0
        for (child in this.children) {
            child.eachPost(parentLevel=parentLevel+1,parentIndex=childIndex,block)
            childIndex+=1
        }
        block(this,parentLevel,parentIndex)
    }
    fun eachLevel(parentLevel: Int=0, parentIndex: Int=0, block: (node: DOMNode, level: Int, index: Int)->Unit){
        var childIndex=0
        for (child in this.children) {
            child.eachPost(parentLevel=parentLevel+1,parentIndex=childIndex,block)
            childIndex+=1
        }
        block(this,parentLevel,parentIndex)
    }
}