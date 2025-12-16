
class TopSoil:
    ph:int
    thickness:int
    def __init__(self,thickness:int, portal : int):
        self.thickness = thickness
        self.ph = portal
    
    def print_me(with_style:string):
        print(f"ph {self.ph}, thickness {thickness}")


def fib(n):    # write Fibonacci series up to n
    """Print a Fibonacci series up to n."""
    a, b = 0, 1
    while a < n:
        print(a, end=' ')
        a, b = b, a+b
    print()



# Now call the function we just defined:
fib(2000)
ts = TopSoil(10,20)
ts.print_me(with_style="wow")


# From https://docs.python.org/3/tutorial/controlflow.html#defining-functions