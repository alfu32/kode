package react

class ContentBox(
    var top: Int=Int.MAX_VALUE,
    var left: Int=Int.MAX_VALUE,
    var bottom: Int = if(top==Int.MAX_VALUE)Int.MIN_VALUE else top,
    var right: Int= if(left==Int.MAX_VALUE)Int.MIN_VALUE else left,
){
    val isEmpty: Boolean get() = top > bottom || left > right

    fun contains(x:Int,y:Int):Boolean{
        return !isEmpty && x in left until (right+1) && y in top until (bottom+1)
    }
    fun contains(b:ContentBox): Boolean {
        return !b.isEmpty && !isEmpty && contains(b.left,b.top) && contains(b.right,b.bottom)
    }
    fun add(x:Int,y:Int){
        top = Math.min(top,y)
        left = Math.min(left,x)
        bottom = Math.max(bottom,y)
        right = Math.max(right,x)
    }
    fun add(b:ContentBox){
        top = Math.min(top,b.top)
        left = Math.min(left,b.left)
        bottom = Math.max(bottom,b.bottom)
        right = Math.max(right,b.right)
    }

    fun movedBy(offsetX: Int, offsetY: Int) : ContentBox{
        top+=offsetY
        left+=offsetX
        bottom+=offsetY
        right+=offsetX
        return this
    }

    override fun toString(): String {
        return "[X$left-${right}xY$top-$bottom]"
    }
}